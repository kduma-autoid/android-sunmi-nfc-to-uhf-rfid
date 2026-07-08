package dev.duma.android.nfctorfid.uhf

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.rscja.deviceapi.RFIDWithUHFBLE
import com.rscja.deviceapi.RFIDWithUHFUSB
import com.rscja.deviceapi.entity.UHFTAGInfo
import com.rscja.deviceapi.interfaces.ConnectionStatus
import com.rscja.deviceapi.interfaces.ConnectionStatusCallback
import com.rscja.deviceapi.interfaces.IUHF
import com.rscja.deviceapi.interfaces.KeyEventCallback
import dev.duma.android.nfctorfid.epc.EpcCodec
import dev.duma.android.nfctorfid.epc.hexToBytesOrNull
import dev.duma.android.nfctorfid.epc.toHex
import dev.duma.android.nfctorfid.uhf.UhfController.ReaderState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared logic for Chainway readers (USB and BLE) over the common [IUHF] API.
 *
 * Unlike the Sunmi SDK there are no async callbacks to correlate: every access
 * command is a blocking call returning a boolean, with the target-tag filter
 * passed inline (filter bank/ptr/len/data — the equivalent of Sunmi's
 * setAccessEpcMatch). Commands run on IO and are serialized by a mutex.
 */
abstract class ChainwayUhfController(context: Context) : UhfController {

    protected val appContext: Context = context.applicationContext
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected val mutableState = MutableStateFlow<ReaderState>(ReaderState.Disconnected)
    override val state: StateFlow<ReaderState> = mutableState

    protected val mutableTriggerEvents = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val triggerEvents: SharedFlow<Boolean> = mutableTriggerEvents

    /** The vendor SDK singleton; both RFIDWithUHFUSB and RFIDWithUHFBLE implement IUHF. */
    protected abstract val uhf: IUHF

    protected abstract fun setPowerRaw(dbm: Int): Boolean

    /** Forwards the reader's own trigger key into [triggerEvents]. */
    protected val keyEventCallback = object : KeyEventCallback {
        override fun onKeyDown(keycode: Int) {
            mutableTriggerEvents.tryEmit(true)
        }

        override fun onKeyUp(keycode: Int) {
            mutableTriggerEvents.tryEmit(false)
        }
    }

    private val opMutex = Mutex()

    override suspend fun awaitReady(timeoutMs: Long) {
        if (mutableState.value is ReaderState.Ready) return
        connect()
        val settled = withTimeoutOrNull(timeoutMs) {
            state.first { it is ReaderState.Ready || it is ReaderState.NoReader }
        }
        if (settled !is ReaderState.Ready) {
            throw UhfError.NotReady("No UHF reader detected")
        }
    }

    override suspend fun applyPower(dbm: Int) {
        if (mutableState.value !is ReaderState.Ready) return
        withContext(Dispatchers.IO) {
            runCatching { setPowerRaw(dbm.coerceIn(POWER_RANGE)) }
        }
    }

    @Volatile
    private var inventoryMode = InventoryMode.HIGH_SPEED

    override suspend fun applyInventoryMode(mode: InventoryMode) {
        inventoryMode = mode
        if (mutableState.value is ReaderState.Ready) applyGen2Session(mode.gen2Session)
    }

    /**
     * Chainway keeps the Gen2 query parameters in the module configuration —
     * read-modify-write only the session and target, best-effort.
     */
    private suspend fun applyGen2Session(session: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                val gen2 = uhf.gen2 ?: return@runCatching
                gen2.querySession = session
                gen2.queryTarget = 0 // target A
                uhf.setGen2(gen2)
            }
        }
    }

    override suspend fun scanTags(
        durationMs: Long,
        onUpdate: ((Map<String, TagInfo>) -> Unit)?,
    ): Map<String, TagInfo> {
        awaitReady()
        return opMutex.withLock {
            val tags = ConcurrentHashMap<String, TagInfo>()
            withContext(Dispatchers.IO) {
                // Poll the tag buffer like the vendor demo does — the inventory
                // callback path has been seen delivering tags without RSSI.
                val started = runCatching { uhf.startInventoryTag() }.getOrDefault(false)
                if (!started) throw UhfError.NotReady("inventory did not start")
                try {
                    val deadline = SystemClock.elapsedRealtime() + durationMs
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val info = runCatching { uhf.readTagFromBuffer() }.getOrNull()
                        if (info == null) {
                            delay(POLL_IDLE_MS)
                            continue
                        }
                        parseTag(info)?.let { seen ->
                            tags.merge(seen.epcHex, seen) { old, new ->
                                old.copy(
                                    rssiDbm = new.rssiDbm ?: old.rssiDbm,
                                    readCount = old.readCount + new.readCount,
                                    pcHex = if (new.pcHex.isNotEmpty()) new.pcHex else old.pcHex,
                                )
                            }
                            onUpdate?.invoke(tags)
                        }
                    }
                } finally {
                    runCatching { uhf.stopInventory() }
                    // Drain leftovers so the next window starts clean.
                    while (runCatching { uhf.readTagFromBuffer() }.getOrNull() != null) Unit
                }
            }
            tags
        }
    }

    override suspend fun writeAndLock(
        currentEpcHex: String,
        uid: ByteArray,
        accessPwd: ByteArray?,
        killPwd: ByteArray?,
        lockEnabled: Boolean,
        skipEpcWrite: Boolean,
        readPowerDbm: Int?,
        writePowerDbm: Int?,
        onStep: (WriteStep) -> Unit,
    ) {
        awaitReady()
        val newEpcHex = EpcCodec.encodeEpc(uid).toHex()
        val currentEpcUpper = currentEpcHex.uppercase().replace(" ", "")
        if (currentEpcUpper.hexToBytesOrNull() == null) {
            throw UhfError.Command(UhfError.ERR_INVALID_PARAMETER, "bad current EPC")
        }

        opMutex.withLock {
            // Every command carries an inline filter on the target EPC, so a foreign
            // tag entering the field cannot take the write; the identical-EPC-twin
            // case is caught by the post-write verification below.
            var targetEpc = currentEpcUpper
            onStep(WriteStep.TARGETING)
            // Writes go out at higher power — they are filtered to one EPC anyway.
            if (writePowerDbm != null) applyPower(writePowerDbm)
            try {
                if (!skipEpcWrite) {
                    onStep(WriteStep.WRITING_EPC)
                    writeFiltered(
                        targetEpcHex = targetEpc,
                        bank = IUHF.Bank_EPC,
                        wordAddress = 0x01,
                        data = EpcCodec.encodePcAndEpc(uid),
                        accessPwd = accessPwd,
                        operation = "write EPC",
                    )
                    targetEpc = newEpcHex
                }

                if (lockEnabled && accessPwd != null) {
                    onStep(WriteStep.WRITING_PASSWORDS)
                    // Probe first: when the tag already holds the expected passwords
                    // (re-scan of an encoded tag), skip the writes entirely — rewriting
                    // a locked reserved bank fails.
                    val expectedReserved = (killPwd ?: ZERO_PWD) + accessPwd
                    val alreadySecured =
                        readReservedBank(targetEpc, accessPwd)?.contentEquals(expectedReserved) == true
                    if (!alreadySecured) {
                        if (killPwd != null) {
                            writeFiltered(
                                targetEpcHex = targetEpc,
                                bank = IUHF.Bank_RESERVED,
                                wordAddress = 0x00,
                                data = killPwd,
                                accessPwd = accessPwd,
                                operation = "write kill password",
                                configuredFirst = skipEpcWrite,
                            )
                        }
                        writeFiltered(
                            targetEpcHex = targetEpc,
                            bank = IUHF.Bank_RESERVED,
                            wordAddress = 0x02,
                            data = accessPwd,
                            accessPwd = accessPwd,
                            operation = "write access password",
                            configuredFirst = skipEpcWrite,
                        )
                    }

                    onStep(WriteStep.LOCKING)
                    lockBanks(targetEpc, accessPwd)
                }
            } finally {
                if (writePowerDbm != null && readPowerDbm != null) applyPower(readPowerDbm)
            }
        }

        // Verification compares the EPC only (same policy as the Sunmi path).
        onStep(WriteStep.VERIFYING)
        var newTagSeen = false
        var oldStillPresent = false
        // Always verify in high-speed (S0): a tag silenced by S1/S2 session
        // persistence would fail the read-back falsely.
        val configuredMode = inventoryMode
        if (configuredMode != InventoryMode.HIGH_SPEED) {
            applyGen2Session(InventoryMode.HIGH_SPEED.gen2Session)
        }
        try {
            repeat(VERIFY_ATTEMPTS) {
                if (newTagSeen) return@repeat
                val readBack = scanTags(VERIFY_WINDOW_MS)
                newTagSeen = readBack.containsKey(newEpcHex)
                // A twin tag with the identical old EPC would have been hidden by
                // EPC-keyed dedup during the scan; if the old EPC is still in field,
                // one twin took the write and the other did not.
                oldStillPresent = !skipEpcWrite && currentEpcUpper != newEpcHex &&
                    readBack.containsKey(currentEpcUpper)
            }
        } finally {
            if (configuredMode != InventoryMode.HIGH_SPEED) {
                applyGen2Session(configuredMode.gen2Session)
            }
        }
        if (!newTagSeen) {
            throw UhfError.VerifyFailed("tag not read back with expected EPC")
        }
        if (oldStillPresent) {
            throw UhfError.DuplicateTag()
        }
    }

    /**
     * Writes [data] to [bank] at [wordAddress], filtered on the target EPC, trying
     * both the zero password (factory tags) and the configured access password
     * (tags this app locked earlier). [configuredFirst] flips the order for tags
     * that are most likely already secured.
     */
    private suspend fun writeFiltered(
        targetEpcHex: String,
        bank: Int,
        wordAddress: Int,
        data: ByteArray,
        accessPwd: ByteArray?,
        operation: String,
        configuredFirst: Boolean = false,
    ) {
        require(data.size % 2 == 0) { "data must be whole words" }
        val configured = accessPwd?.takeIf { !it.contentEquals(ZERO_PWD) }
        val passwords = if (configuredFirst && configured != null) {
            listOf(configured, ZERO_PWD)
        } else {
            listOfNotNull(ZERO_PWD, configured)
        }
        for (pwd in passwords) {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    uhf.writeData(
                        pwd.toHex(),
                        IUHF.Bank_EPC,
                        FILTER_PTR_BITS,
                        targetEpcHex.length * 4,
                        targetEpcHex,
                        bank,
                        wordAddress,
                        data.size / 2,
                        data.toHex(),
                    )
                }.getOrDefault(false)
            }
            if (ok) return
            // The SDK reports a locked bank / wrong password only as `false` —
            // try the other password before giving up.
        }
        throw UhfError.Command(UhfError.ERR_TAG_WRITE, operation)
    }

    /**
     * Reads the reserved bank (kill + access password, words 0–3) in Secured
     * state; null when the tag refuses (wrong password or unsecured tag).
     */
    private suspend fun readReservedBank(targetEpcHex: String, accessPwd: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                uhf.readData(
                    accessPwd.toHex(),
                    IUHF.Bank_EPC,
                    FILTER_PTR_BITS,
                    targetEpcHex.length * 4,
                    targetEpcHex,
                    IUHF.Bank_RESERVED,
                    0x00,
                    0x04,
                )
            }.getOrNull()?.replace(" ", "")?.hexToBytesOrNull()
        }

    /** Locks kill password, access password and the EPC bank in one command. */
    private suspend fun lockBanks(targetEpcHex: String, accessPwd: ByteArray) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val code = uhf.generateLockCode(
                    arrayListOf(IUHF.LockBank_KILL, IUHF.LockBank_ACCESS, IUHF.LockBank_EPC),
                    IUHF.LockMode_LOCK,
                )
                uhf.lockMem(
                    accessPwd.toHex(),
                    IUHF.Bank_EPC,
                    FILTER_PTR_BITS,
                    targetEpcHex.length * 4,
                    targetEpcHex,
                    code,
                )
            }.getOrDefault(false)
        }
        if (!ok) throw UhfError.Command(UhfError.ERR_ACCESS_FAIL, "lock banks")
    }

    private fun parseTag(info: UHFTAGInfo?): TagInfo? {
        val epc = info?.epc?.replace(" ", "")?.uppercase() ?: return null
        if (epc.isEmpty()) return null
        return TagInfo(
            epcHex = epc,
            pcHex = info.pc?.replace(" ", "")?.uppercase() ?: "",
            rssiDbm = parseRssi(info.rssi),
            readCount = 1,
        )
    }

    /**
     * Chainway reports RSSI as a decimal dBm string, but the exact shape varies
     * by module/firmware: "-51.20", "-51,20" (locale comma), or with a unit
     * suffix. Null for anything non-numeric.
     */
    private fun parseRssi(raw: String?): Int? {
        val cleaned = raw?.trim()
            ?.removeSuffix("dBm")?.removeSuffix("dbm")
            ?.trim()?.replace(',', '.')
            ?: return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return value.roundToInt()
    }

    protected companion object {
        /** Output power supported by Chainway modules. */
        val POWER_RANGE = 1..30

        /** EPC data starts at bit 32 of the EPC bank (after CRC + PC words). */
        const val FILTER_PTR_BITS = 32

        val ZERO_PWD = ByteArray(4)

        const val POLL_IDLE_MS = 5L
        const val VERIFY_WINDOW_MS = 1_200L
        const val VERIFY_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val USB_PERMISSION_TIMEOUT_MS = 30_000L
    }
}

/** Chainway reader attached over USB (e.g. R3 desktop reader). */
class ChainwayUsbUhfController(context: Context) : ChainwayUhfController(context) {

    override val backend = ReaderBackend.CHAINWAY_USB

    private val usb: RFIDWithUHFUSB get() = RFIDWithUHFUSB.getInstance()
    override val uhf: IUHF get() = usb

    override fun setPowerRaw(dbm: Int): Boolean = usb.setPower(dbm)

    private val connecting = AtomicBoolean(false)

    /** True when a Chainway USB reader is attached (permission not required). */
    fun hasDevice(): Boolean =
        runCatching { usb.getUsbDeviceList(appContext) }.getOrNull()?.isNotEmpty() == true

    override fun connect() {
        if (mutableState.value is ReaderState.Ready) return
        if (!connecting.compareAndSet(false, true)) return
        mutableState.value = ReaderState.Connecting
        scope.launch {
            try {
                val device =
                    runCatching { usb.getUsbDeviceList(appContext) }.getOrNull()?.firstOrNull()
                if (device == null) {
                    mutableState.value = ReaderState.NoReader
                    return@launch
                }
                if (!requestUsbPermission(device)) {
                    mutableState.value = ReaderState.NoReader
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching { usb.init(device, appContext) }.getOrDefault(false)
                }
                if (!ok) {
                    mutableState.value = ReaderState.NoReader
                    return@launch
                }
                runCatching { usb.setKeyEventCallback(keyEventCallback) }
                mutableState.value = ReaderState.Ready("Chainway USB")
            } finally {
                connecting.set(false)
            }
        }
    }

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) return true

        val action = RFIDWithUHFUSB.ACTION_USB_PERMISSION
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != action) return
                result.complete(
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                )
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            // Explicit (package-targeted) intent + FLAG_MUTABLE: required on newer
            // Android so the system can both deliver and fill in the result extras.
            val pending = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(action).setPackage(appContext.packageName),
                PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, pending)
            withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) { result.await() } == true
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    override fun disconnect() {
        scope.launch(Dispatchers.IO) {
            if (mutableState.value is ReaderState.Ready) runCatching { usb.free() }
            mutableState.value = ReaderState.Disconnected
        }
    }
}

/** Chainway reader connected over Bluetooth LE (e.g. R2 / R3 handheld). */
class ChainwayBleUhfController(
    context: Context,
    private val deviceMac: () -> String?,
    private val deviceName: () -> String?,
) : ChainwayUhfController(context) {

    override val backend = ReaderBackend.CHAINWAY_BLE

    private val ble: RFIDWithUHFBLE get() = RFIDWithUHFBLE.getInstance()
    override val uhf: IUHF get() = ble

    override fun setPowerRaw(dbm: Int): Boolean = ble.setPower(dbm)

    private val connecting = AtomicBoolean(false)

    private val statusCallback = ConnectionStatusCallback<Any?> { status, _ ->
        when (status) {
            ConnectionStatus.CONNECTED -> onLinkUp()
            ConnectionStatus.DISCONNECTED -> mutableState.value = ReaderState.Disconnected
            else -> Unit // CONNECTING — already reflected in our state
        }
    }

    private fun onLinkUp() {
        runCatching {
            ble.setConnectionStatusCallback(statusCallback)
            ble.setKeyEventCallback(keyEventCallback)
        }
        mutableState.value = ReaderState.Ready(readerLabel())
    }

    override fun connect() {
        val mac = deviceMac()
        if (mac == null) {
            mutableState.value = ReaderState.NoReader
            return
        }
        if (mutableState.value is ReaderState.Ready) return
        if (!connecting.compareAndSet(false, true)) return
        mutableState.value = ReaderState.Connecting
        scope.launch {
            try {
                BleSdk.ensureInit(appContext)
                if (ble.connectStatus == ConnectionStatus.CONNECTED) {
                    onLinkUp()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    runCatching { ble.connect(mac, statusCallback) }
                }
                val settled = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    state.first { it !is ReaderState.Connecting }
                }
                if (settled == null) {
                    withContext(Dispatchers.IO) { runCatching { ble.disconnect() } }
                    mutableState.value = ReaderState.NoReader
                }
            } finally {
                connecting.set(false)
            }
        }
    }

    override fun disconnect() {
        scope.launch(Dispatchers.IO) {
            runCatching { ble.disconnect() }
            mutableState.value = ReaderState.Disconnected
        }
    }

    private fun readerLabel(): String {
        val name = deviceName()
        val mac = deviceMac().orEmpty()
        return if (name.isNullOrBlank()) "Chainway BT $mac" else "$name ($mac)"
    }
}

/** One-time init guard for the Chainway BLE SDK (also needed before BT scans). */
object BleSdk {
    @Volatile
    private var initialized = false

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching { RFIDWithUHFBLE.getInstance().init(context.applicationContext) }
            initialized = true
        }
    }
}
