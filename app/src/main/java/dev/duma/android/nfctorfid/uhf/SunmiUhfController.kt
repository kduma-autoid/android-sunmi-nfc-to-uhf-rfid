package dev.duma.android.nfctorfid.uhf

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sunmi.rfid.RFIDHelper
import com.sunmi.rfid.RFIDManager
import com.sunmi.rfid.ReaderCall
import com.sunmi.rfid.constant.CMD
import com.sunmi.rfid.constant.ParamCts
import com.sunmi.rfid.entity.DataParameter
import com.sunmi.sdk.ServiceConnectStatus
import dev.duma.android.nfctorfid.epc.EpcCodec
import dev.duma.android.nfctorfid.epc.hexToBytesOrNull
import dev.duma.android.nfctorfid.epc.toHex
import dev.duma.android.nfctorfid.uhf.UhfController.ReaderState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Single owner of the Sunmi RFID SDK connection.
 *
 * All SDK commands are asynchronous void calls answered through one registered
 * [ReaderCall] (callbacks arrive on Binder threads). This controller serializes
 * commands with a [Mutex], bridges each one to a coroutine and guards it with a
 * timeout — mandatory, because after a service death the SDK swallows errors and
 * no callback ever arrives.
 */
class SunmiUhfController(private val context: Context) : UhfController {

    override val backend = ReaderBackend.SUNMI

    private val manager: RFIDManager get() = RFIDManager.getInstance()

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Disconnected)
    override val state: StateFlow<ReaderState> = _state

    /** Sunmi reports the trigger as a key event — this flow never emits. */
    override val triggerEvents: SharedFlow<Boolean> = MutableSharedFlow()

    @Volatile
    private var helper: RFIDHelper? = null

    private val commandMutex = Mutex()

    private class PendingCommand(
        val cmds: Set<Byte>,
        val continuation: CancellableContinuation<CommandResult>,
        val completed: AtomicBoolean = AtomicBoolean(false),
    )

    private sealed class CommandResult {
        class Success(val params: DataParameter?) : CommandResult()
        class Failure(val errorCode: Int, val message: String?) : CommandResult()
    }

    private val pending = AtomicReference<PendingCommand?>(null)

    /**
     * CMD bytes of commands that timed out or were cancelled while in flight.
     * The SDK callback carries no correlation token, so a late answer from an
     * abandoned command would otherwise complete the NEXT command with the same
     * CMD byte. Each terminal callback first consumes one stale entry (if any)
     * and is dropped; entries expire after [STALE_HORIZON_MS] to cover the
     * callback-never-arrives case (service death).
     */
    private val staleLock = Any()
    private val staleCmds = HashMap<Byte, MutableList<Long>>()

    private fun recordStale(cmds: Set<Byte>) {
        val now = SystemClock.elapsedRealtime()
        synchronized(staleLock) {
            for (cmd in cmds) staleCmds.getOrPut(cmd) { mutableListOf() }.add(now)
        }
    }

    private fun consumeStale(cmd: Byte): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(staleLock) {
            val entries = staleCmds[cmd] ?: return false
            entries.removeAll { now - it > STALE_HORIZON_MS }
            if (entries.isEmpty()) {
                staleCmds.remove(cmd)
                return false
            }
            entries.removeAt(0)
            if (entries.isEmpty()) staleCmds.remove(cmd)
            return true
        }
    }

    private fun clearStale() {
        synchronized(staleLock) { staleCmds.clear() }
    }

    @Volatile
    private var tagSink: ((DataParameter) -> Unit)? = null

    private val readerCall = object : ReaderCall() {
        override fun onSuccess(cmd: Byte, params: DataParameter?) {
            Log.d(TAG, "onSuccess cmd=0x%02X params=%s".format(cmd, params != null))
            if (isInventoryCmd(cmd)) streamRunning = false
            runCatching { completePending(cmd) { CommandResult.Success(params) } }
        }

        override fun onTag(cmd: Byte, state: Byte, tag: DataParameter?) {
            Log.d(
                TAG,
                "onTag cmd=0x%02X state=0x%02X epc=%s".format(
                    cmd, state, tag?.getString(ParamCts.TAG_EPC) ?: "-",
                ),
            )
            runCatching { if (tag != null) tagSink?.invoke(tag) }
        }

        override fun onFailed(cmd: Byte, errorCode: Byte, msg: String?) {
            Log.d(
                TAG,
                "onFailed cmd=0x%02X error=0x%02X msg=%s".format(
                    cmd, errorCode.toInt() and 0xFF, msg,
                ),
            )
            if (isInventoryCmd(cmd)) streamRunning = false
            runCatching { completePending(cmd) { CommandResult.Failure(errorCode.toInt() and 0xFF, msg) } }
        }
    }

    private fun isInventoryCmd(cmd: Byte): Boolean =
        cmd == CMD.INVENTORY || cmd == CMD.REAL_TIME_INVENTORY ||
            cmd == CMD.CUSTOMIZED_SESSION_TARGET_INVENTORY

    private fun completePending(cmd: Byte, result: () -> CommandResult) {
        // A late answer from a timed-out/cancelled command must not complete the
        // current one — consume and drop exactly one stale entry for this CMD.
        if (consumeStale(cmd)) return
        val p = pending.get() ?: return
        if (cmd !in p.cmds) {
            // Diagnostic for module quirks: an answer arriving under an unexpected
            // CMD byte would otherwise stall the command until its timeout.
            Log.d(TAG, "answer 0x%02X ignored, waiting for %s".format(
                cmd, p.cmds.joinToString { "0x%02X".format(it) },
            ))
            return
        }
        if (!p.completed.compareAndSet(false, true)) return
        if (p.continuation.isActive) p.continuation.resume(result())
    }

    private fun failPending(error: UhfError) {
        val p = pending.get() ?: return
        if (!p.completed.compareAndSet(false, true)) return
        if (p.continuation.isActive) runCatching { p.continuation.resumeWithException(error) }
    }

    private val connectStatus = object : ServiceConnectStatus {
        override fun onServiceConnected() {
            Log.d(TAG, "service connected")
            onServiceReady()
        }

        override fun onServiceDisconnected() {
            Log.d(TAG, "service disconnected")
            helper = null
            clearStale()
            _state.value = ReaderState.Disconnected
            failPending(UhfError.ServiceLost())
        }
    }

    /** Starts connecting to the Sunmi scanner service; safe to call repeatedly. */
    override fun connect() {
        if (_state.value is ReaderState.Ready) return
        Log.d(TAG, "connect(), state=${_state.value}")
        _state.value = ReaderState.Connecting
        // SDK-internal logging — invaluable when a device misbehaves in the field.
        manager.setPrintLog(true)
        // addServiceConnectStatus appends without dedup — remove first so repeated
        // connect() calls never register the listener twice.
        manager.removeServiceConnectStatus(connectStatus)
        manager.addServiceConnectStatus(connectStatus)
        manager.connect(context.applicationContext)
        // connect() is a no-op when the service is already bound; in that case the
        // callback may not fire again, so probe the helper directly.
        if (manager.isConnect()) onServiceReady()
    }

    override fun disconnect() {
        streamStopJob?.cancel()
        // Demo-parity stop before letting go of the reader.
        if (streamRunning) helper?.let { runCatching { it.inventory(1) } }
        streamRunning = false
        helper?.let { runCatching { it.unregisterReaderCall() } }
        helper = null
        clearStale()
        manager.removeServiceConnectStatus(connectStatus)
        runCatching { manager.disconnect() }
        _state.value = ReaderState.Disconnected
    }

    @Volatile
    private var scanModel = MODEL_NONE

    /** Send a stale-inventory-breaking `inventory(1)` before the next command. */
    @Volatile
    private var takeoverPending = true

    /** True while a session inventory round is running on a stream-model module. */
    @Volatile
    private var streamRunning = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var streamStopJob: Job? = null


    private fun onServiceReady() {
        // Idempotent: connect() may trigger this both via the listener and the probe.
        if (helper != null && _state.value is ReaderState.Ready) return
        val h = try {
            manager.getHelper()
        } catch (e: RuntimeException) {
            null
        } ?: run {
            _state.value = ReaderState.NoReader
            return
        }
        helper = h
        runCatching { h.registerReaderCall(readerCall) }
        takeoverPending = true
        val model = try {
            h.getScanModel()
        } catch (e: Exception) {
            MODEL_NONE
        }
        scanModel = model
        Log.d(TAG, "service ready, scanModel=$model (${modelName(model)})")
        // The SDK returns -1 (instead of throwing) when the binder is dead.
        _state.value =
            if (model <= MODEL_NONE) ReaderState.NoReader else ReaderState.Ready(modelName(model))
    }

    /** Suspends until the reader is ready. */
    override suspend fun awaitReady(timeoutMs: Long) {
        if (_state.value is ReaderState.Ready) return
        connect()
        val settled = try {
            withTimeout(timeoutMs) {
                state.first { it is ReaderState.Ready || it is ReaderState.NoReader }
            }
        } catch (e: TimeoutCancellationException) {
            throw UhfError.NotReady("RFID service did not connect in time")
        }
        if (settled !is ReaderState.Ready) throw UhfError.NotReady("No UHF reader detected")
    }

    private fun requireHelper(): RFIDHelper = helper ?: throw UhfError.NotReady()

    /**
     * Issues one SDK command and awaits its onSuccess/onFailed callback.
     * [cmds] lists the CMD byte(s) the reader answers this command with.
     */
    private suspend fun execute(
        cmds: Set<Byte>,
        operation: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        command: (RFIDHelper) -> Unit,
    ): CommandResult = commandMutex.withLock {
        val h = requireHelper()
        if (takeoverPending) {
            // The scanner service is a persistent process; a client killed without
            // unregistering leaves a dead callback entry in its table, after which
            // answers hit "Setting operate Right call is Null!" and tag streams go
            // to the dead binder. Unregister first to clear our package's stale
            // entry, then install the live callback.
            runCatching { h.unregisterReaderCall() }
                .onFailure { Log.w(TAG, "unregisterReaderCall failed: $it") }
        }
        // Like Sunmi's demo app: (re-)register the reader call right before the
        // command. The registration is a single slot, so this is idempotent — and
        // a registration issued the moment the service reported ready can be lost
        // on some devices (L3), leaving every callback undelivered.
        runCatching { h.registerReaderCall(readerCall) }
            .onFailure { Log.w(TAG, "registerReaderCall failed: $it") }
        // Non-inventory commands interleave with a running inventory round just
        // fine (~0.3 s, observed in service logs) — no need to wait it out.
        Log.d(
            TAG,
            "issue '$operation' expecting ${cmds.joinToString { "0x%02X".format(it) }} timeout=${timeoutMs}ms",
        )
        try {
            val result = withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val p = PendingCommand(cmds, cont)
                    pending.set(p)
                    try {
                        command(h)
                    } catch (e: Exception) {
                        Log.w(TAG, "'$operation' SDK call threw: $e")
                        if (p.completed.compareAndSet(false, true) && cont.isActive) {
                            cont.resumeWithException(UhfError.NotReady(e.message ?: "SDK call failed"))
                        }
                    }
                }
            }
            when (result) {
                is CommandResult.Success -> Log.d(TAG, "'$operation' -> success")
                is CommandResult.Failure ->
                    Log.d(TAG, "'$operation' -> failed 0x%02X %s".format(result.errorCode, result.message))
            }
            result
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "'$operation' timed out after ${timeoutMs}ms (no matching callback)")
            // The silence may mean the module got re-wedged — try to take it
            // over again before the next command.
            takeoverPending = true
            recordStale(cmds)
            throw UhfError.Timeout(operation)
        } catch (e: CancellationException) {
            // Externally cancelled with a command in flight — its answer may still come.
            recordStale(cmds)
            throw e
        } finally {
            pending.set(null)
        }
    }

    private suspend fun executeOrThrow(
        cmds: Set<Byte>,
        operation: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        command: (RFIDHelper) -> Unit,
    ): DataParameter? = when (val r = execute(cmds, operation, timeoutMs, command)) {
        is CommandResult.Success -> r.params
        is CommandResult.Failure -> throw UhfError.Command(r.errorCode, r.message)
    }

    override suspend fun applyPower(dbm: Int) {
        runCatching {
            execute(setOf(CMD.SET_OUTPUT_POWER, CMD.SET_TEMPORARY_OUTPUT_POWER), "set power") {
                it.setOutputAllPower(dbm.toByte())
            }
        }
    }

    @Volatile
    private var inventoryMode = InventoryMode.HIGH_SPEED

    override suspend fun applyInventoryMode(mode: InventoryMode) {
        // The mode is applied per inventory round command — nothing to send now.
        inventoryMode = mode
    }

    /**
     * Runs inventory rounds until [durationMs] elapses and returns unique tags by EPC.
     * [onUpdate] fires on a Binder thread after each new observation.
     */
    override suspend fun scanTags(
        durationMs: Long,
        onUpdate: ((Map<String, TagInfo>) -> Unit)?,
    ): Map<String, TagInfo> = scanTagsWith(inventoryMode, durationMs, onUpdate)

    private suspend fun scanTagsWith(
        mode: InventoryMode,
        durationMs: Long,
        onUpdate: ((Map<String, TagInfo>) -> Unit)? = null,
    ): Map<String, TagInfo> {
        awaitReady()
        val tags = ConcurrentHashMap<String, TagInfo>()
        tagSink = { data ->
            parseTag(data)?.let { seen ->
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
        try {
            if (inventoryRepeat(scanModel) > 1) {
                // Newer modules (S7100/SIM3500/YRF808S) treat an inventory command
                // as a long-running stream — the terminal callback only arrives
                // when the stream is aborted, so a round-based loop would time out
                // while tags flow in.
                streamScan(mode, durationMs)
            } else {
                roundLoopScan(mode, durationMs)
            }
        } finally {
            tagSink = null
        }
        return tags
    }

    /** Round-based scanning for the older modules (R2000 handle, M500). */
    private suspend fun roundLoopScan(mode: InventoryMode, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        // Some modules answer an inventory command with the OTHER inventory CMD
        // byte — accept both, like Sunmi's demo app does.
        val roundCmds = setOf(CMD.REAL_TIME_INVENTORY, CMD.CUSTOMIZED_SESSION_TARGET_INVENTORY)
        while (SystemClock.elapsedRealtime() < deadline) {
            // A round with no tags reports onFailed — keep scanning until the deadline.
            val result = execute(roundCmds, "inventory round", INVENTORY_ROUND_TIMEOUT_MS) {
                issueInventory(it, mode, 1)
            }
            if (result is CommandResult.Failure &&
                result.errorCode == UhfError.ERR_INVALID_PARAMETER
            ) {
                throw UhfError.Command(result.errorCode, result.message)
            }
        }
    }

    /**
     * Stream-based scanning for the newer modules. An inventory round on these
     * modules is LONG (~10-20 s regardless of the repeat argument), cannot be
     * aborted mid-flight, and every queued inventory command replays another
     * full round — so windows must NOT issue a command each. Instead one round
     * is kept alive for the whole scanning session: windows only collect the
     * streamed tags, the round is re-issued when its terminal answer arrives
     * ([streamRunning] flips false), and an idle watchdog breaks the round with
     * inventory(1) once no window has followed for a moment.
     */
    private suspend fun streamScan(mode: InventoryMode, durationMs: Long) {
        streamStopJob?.cancel()
        commandMutex.withLock {
            val h = requireHelper()
            if (takeoverPending) {
                takeoverPending = false
                // Clear a possible stale (dead-process) callback entry in the
                // service before installing ours.
                runCatching { h.unregisterReaderCall() }
            }
            runCatching { h.registerReaderCall(readerCall) }
            if (!streamRunning) {
                Log.d(TAG, "stream scan: starting inventory round (mode=$mode)")
                runCatching { issueInventory(h, mode, STREAM_ROUND_REPEAT) }
                streamRunning = true
            }
            try {
                delay(durationMs)
            } finally {
                // The round stays alive for the next window; the idle watchdog
                // stops it only when no window follows.
                scheduleIdleStop()
            }
        }
    }

    /**
     * Stops the session inventory once scanning windows stop coming, using the
     * vendor demo's own stop mechanism: `inventory(1)`. On the newer scanner
     * service (per-command callback routing) this interrupts the continuous
     * inventory; on the older one it can only queue behind the round — which is
     * exactly what the vendor demo does there too.
     */
    private fun scheduleIdleStop() {
        streamStopJob?.cancel()
        streamStopJob = scope.launch {
            delay(STREAM_IDLE_STOP_MS)
            commandMutex.withLock {
                if (!streamRunning) return@withLock
                val h = helper ?: return@withLock
                Log.d(TAG, "idle: stopping inventory with inventory(1)")
                if (stopInventoryStream(h)) {
                    Log.d(TAG, "idle: inventory stopped")
                    streamRunning = false
                } else {
                    // The round is still on the air — keep streamRunning so the
                    // next window reuses it instead of stacking a second
                    // inventory on top, and retry the stop on the next idle.
                    Log.w(TAG, "idle: inventory stop got no answer, will retry")
                    scheduleIdleStop()
                }
            }
        }
    }

    /** Sends `inventory(1)` and consumes its answer; false on timeout. */
    private suspend fun stopInventoryStream(h: RFIDHelper): Boolean {
        val cmds = setOf(
            CMD.INVENTORY,
            CMD.REAL_TIME_INVENTORY,
            CMD.CUSTOMIZED_SESSION_TARGET_INVENTORY,
        )
        return try {
            withTimeout(STREAM_STOP_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    pending.set(PendingCommand(cmds, cont))
                    runCatching { h.inventory(1) }
                }
            }
            true
        } catch (e: TimeoutCancellationException) {
            recordStale(cmds)
            false
        } finally {
            pending.set(null)
        }
    }


    private fun issueInventory(h: RFIDHelper, mode: InventoryMode, repeat: Byte) {
        when (mode) {
            InventoryMode.HIGH_SPEED -> h.realTimeInventory(repeat)

            // Same arguments as Sunmi's own demo app uses for the balance and
            // traversal modes: (session, target A, 0, 0, no power-save, repeat).
            // FastTID is intentionally not enabled.
            else -> h.customizedSessionTargetInventory(
                mode.gen2Session.toByte(), 0x00, 0x00, 0x00, 0x00, repeat,
            )
        }
    }

    /**
     * Full encode sequence against the single tag currently in field:
     * write EPC (skippable) → write kill+access passwords → lock banks → verify.
     * Throws [UhfError]; partially-completed sequences are safe to re-run.
     */
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
        val newEpc = EpcCodec.encodeEpc(uid)
        val newEpcHex = newEpc.toHex()
        val currentEpc = currentEpcHex.hexToBytesOrNull()
            ?: throw UhfError.Command(UhfError.ERR_INVALID_PARAMETER, "bad current EPC")
        val currentEpcUpper = currentEpcHex.uppercase().replace(" ", "")

        // No pre-write field check: setAccessEpcMatch targets the scanned EPC, so a
        // foreign tag entering the field cannot take the write, and a vanished target
        // surfaces as a reader error (0x36/0x37). The identical-EPC-twin case is
        // caught after the write by the old-EPC-still-present verification.
        onStep(WriteStep.TARGETING)
        // Writes go out at higher power — they are filtered to one EPC anyway.
        if (writePowerDbm != null) applyPower(writePowerDbm)
        try {
            epcMatch(currentEpc)

            if (!skipEpcWrite) {
                onStep(WriteStep.WRITING_EPC)
                writeWithPasswordFallback(
                    bank = BANK_EPC,
                    wordAddress = 0x01,
                    data = EpcCodec.encodePcAndEpc(uid),
                    accessPwd = accessPwd,
                    operation = "write EPC",
                )
                epcMatch(newEpc)
            }

            if (lockEnabled && accessPwd != null) {
                onStep(WriteStep.WRITING_PASSWORDS)
                // Probe first: when the tag already holds the expected passwords
                // (re-scan of an encoded tag), skip the writes entirely — rewriting
                // a locked reserved bank fails on some modules (0x33).
                val expectedReserved = (killPwd ?: ZERO_PWD) + accessPwd
                val alreadySecured =
                    readReservedBank(accessPwd)?.contentEquals(expectedReserved) == true
                if (!alreadySecured) {
                    if (killPwd != null) {
                        writeWithPasswordFallback(
                            bank = BANK_RESERVED,
                            wordAddress = 0x00,
                            data = killPwd,
                            accessPwd = accessPwd,
                            operation = "write kill password",
                            configuredFirst = skipEpcWrite,
                        )
                    }
                    writeWithPasswordFallback(
                        bank = BANK_RESERVED,
                        wordAddress = 0x02,
                        data = accessPwd,
                        accessPwd = accessPwd,
                        operation = "write access password",
                        configuredFirst = skipEpcWrite,
                    )
                }

                onStep(WriteStep.LOCKING)
                lock(accessPwd, LOCK_BANK_KILL_PWD, "lock kill password")
                lock(accessPwd, LOCK_BANK_ACCESS_PWD, "lock access password")
                lock(accessPwd, LOCK_BANK_EPC, "lock EPC bank")
            }
        } finally {
            runCatching {
                execute(setOf(CMD.SET_ACCESS_EPC_MATCH), "cancel EPC match") {
                    it.cancelAccessEpcMatch()
                }
            }
            if (writePowerDbm != null && readPowerDbm != null) applyPower(readPowerDbm)
        }

        // Verification compares the EPC only. The PC word is intentionally NOT
        // checked: some chips do not persist the T/AFI bits of a written PC, and
        // the reported EPC length already proves the PC length field took effect.
        onStep(WriteStep.VERIFYING)
        var newTagSeen = false
        var oldStillPresent = false
        repeat(VERIFY_ATTEMPTS) {
            if (newTagSeen) return@repeat
            // Always verify in high-speed (S0): a tag silenced by S1/S2 session
            // persistence would fail the read-back falsely.
            val readBack = scanTagsWith(InventoryMode.HIGH_SPEED, VERIFY_WINDOW_MS)
            newTagSeen = readBack.containsKey(newEpcHex)
            // A twin tag with the identical old EPC would have been hidden by
            // EPC-keyed dedup during the scan; if the old EPC is still in field,
            // one twin took the write and the other did not.
            oldStillPresent = !skipEpcWrite && currentEpcUpper != newEpcHex &&
                readBack.containsKey(currentEpcUpper)
        }
        if (!newTagSeen) {
            throw UhfError.VerifyFailed("tag not read back with expected EPC")
        }
        if (oldStillPresent) {
            throw UhfError.DuplicateTag()
        }
    }

    private suspend fun epcMatch(epc: ByteArray) {
        executeOrThrow(setOf(CMD.SET_ACCESS_EPC_MATCH), "set EPC match") {
            it.setAccessEpcMatch(epc.size.toByte(), epc)
        }
    }

    /**
     * Writes trying both the zero password (factory tags) and the configured
     * access password (tags this app locked earlier). [configuredFirst] flips the
     * order for tags that are most likely already secured.
     */
    private suspend fun writeWithPasswordFallback(
        bank: Byte,
        wordAddress: Int,
        data: ByteArray,
        accessPwd: ByteArray?,
        operation: String,
        configuredFirst: Boolean = false,
    ) {
        require(data.size % 2 == 0) { "data must be whole words" }
        val wordCount = data.size / 2
        val configured = accessPwd?.takeIf { !it.contentEquals(ZERO_PWD) }
        val passwords = if (configuredFirst && configured != null) {
            listOf(configured, ZERO_PWD)
        } else {
            listOfNotNull(ZERO_PWD, configured)
        }
        var lastError: UhfError.Command? = null
        for (pwd in passwords) {
            try {
                executeOrThrow(setOf(CMD.WRITE_TAG), operation, WRITE_TIMEOUT_MS) {
                    it.writeTag(pwd, bank, wordAddress.toByte(), wordCount.toByte(), data)
                }
                return
            } catch (e: UhfError.Command) {
                // Modules report a locked bank / wrong password inconsistently
                // (0x40 access fail, 0x33 write error…) — try the other password
                // before giving up.
                lastError = e
            }
        }
        throw lastError ?: UhfError.Command(UhfError.ERR_ACCESS_FAIL, operation)
    }

    /**
     * Reads the reserved bank (kill + access password, words 0–3) in Secured
     * state; null when the tag refuses (wrong password or unsecured tag).
     * Requires an active EPC match on the target tag.
     */
    private suspend fun readReservedBank(accessPwd: ByteArray): ByteArray? = try {
        val params = executeOrThrow(setOf(CMD.READ_TAG), "read reserved bank", WRITE_TIMEOUT_MS) {
            it.readTag(BANK_RESERVED, 0x00, 0x04, accessPwd)
        }
        params?.getString(ParamCts.TAG_DATA)?.normalizeHex()?.hexToBytesOrNull()
    } catch (e: UhfError.Command) {
        null
    }

    private suspend fun lock(accessPwd: ByteArray, lockBank: Byte, operation: String) {
        executeOrThrow(setOf(CMD.LOCK_TAG), operation, WRITE_TIMEOUT_MS) {
            it.lockTag(accessPwd, lockBank, LOCK_TYPE_LOCK)
        }
    }

    private fun parseTag(data: DataParameter): TagInfo? {
        val epc = data.getString(ParamCts.TAG_EPC)?.normalizeHex() ?: return null
        if (epc.isEmpty()) return null
        val pc = data.getString(ParamCts.TAG_PC)?.normalizeHex() ?: ""
        val readCount = runCatching { data.getInt(ParamCts.TAG_READ_COUNT, 1) }.getOrDefault(1)
        return TagInfo(
            epcHex = epc,
            pcHex = pc,
            rssiDbm = parseRssi(data),
            readCount = if (readCount > 0) readCount else 1,
        )
    }

    private fun parseRssi(data: DataParameter): Int? {
        val raw = runCatching { data.getString(ParamCts.TAG_RSSI) }.getOrNull()
        val value = raw?.trim()?.toIntOrNull()
            ?: runCatching { data.getInt(ParamCts.TAG_RSSI, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            ?: return null
        // The protocol reports RSSI as a positive index: dBm = index - 129 (98 -> -31 dBm).
        return if (value > 0) value - 129 else value
    }

    private fun String.normalizeHex(): String = replace(" ", "").uppercase()

    companion object {
        private const val TAG = "SunmiUhf"
        const val MODEL_NONE = 100

        val PC_HEX: String = "%04X".format(EpcCodec.PC_WORD)

        private val ZERO_PWD = ByteArray(4)

        private const val BANK_RESERVED: Byte = 0x00
        private const val BANK_EPC: Byte = 0x01

        // lockTag() bank codes (differ from read/write bank codes).
        private const val LOCK_BANK_KILL_PWD: Byte = 0x05
        private const val LOCK_BANK_ACCESS_PWD: Byte = 0x04
        private const val LOCK_BANK_EPC: Byte = 0x03

        private const val LOCK_TYPE_LOCK: Byte = 0x01

        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        // Generous: a 50-repeat round on the newer modules can take a while.
        private const val INVENTORY_ROUND_TIMEOUT_MS = 15_000L
        /** Session round length — like the demo; the round is re-issued on expiry. */
        /**
         * One repeat per round. The service processes a queued inventory(1)
         * stop ONLY at a round boundary, and a single repeat already runs
         * ~5-10 s while a tag keeps answering (the vendor demo's repeat=10
         * gives it a 1-2 minute RF tail there) — so the smallest round bounds
         * the post-scan tail best. The scan windows re-issue the round as soon
         * as its terminal arrives, so scanning still streams continuously.
         */
        private const val STREAM_ROUND_REPEAT: Byte = 1
        private const val STREAM_IDLE_STOP_MS = 800L

        /** Must cover a full round — the stop answer waits for the boundary. */
        private const val STREAM_STOP_TIMEOUT_MS = 15_000L
        private const val VERIFY_WINDOW_MS = 1_200L
        private const val STALE_HORIZON_MS = 20_000L
        private const val VERIFY_ATTEMPTS = 3

        fun modelName(model: Int): String = when (model) {
            RFIDManager.UHF_R2000 -> "UHF R2000 (handle)"
            RFIDManager.INNER_M500 -> "Inner M500"
            RFIDManager.UHF_S7100 -> "UHF S7100"
            RFIDManager.INNER_SIM3500 -> "Inner SIM3500"
            RFIDManager.OUTER_YRF808S -> "Outer YRF808S"
            else -> "None"
        }

        /**
         * Inventory rounds per command — mirrors Sunmi's demo app: the newer
         * modules (S7100, SIM3500, YRF808S — e.g. the L3) need 50, the older
         * ones (R2000 handle, M500) run one round per command.
         */
        fun inventoryRepeat(model: Int): Byte = when (model) {
            RFIDManager.UHF_S7100,
            RFIDManager.INNER_SIM3500,
            RFIDManager.OUTER_YRF808S,
            -> 50

            else -> 1
        }
    }
}
