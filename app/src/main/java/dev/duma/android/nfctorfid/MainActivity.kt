package dev.duma.android.nfctorfid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.duma.android.nfctorfid.about.AboutFragment
import dev.duma.android.nfctorfid.databinding.ActivityMainBinding
import dev.duma.android.nfctorfid.encode.EncodeFragment
import dev.duma.android.nfctorfid.nfc.NfcCardConsumer
import dev.duma.android.nfctorfid.nfc.NfcCardReader
import dev.duma.android.nfctorfid.nfc.OriginalityStatus
import dev.duma.android.nfctorfid.scan.ScanFragment
import dev.duma.android.nfctorfid.settings.SettingsDialog
import dev.duma.android.nfctorfid.uhf.ChainwayBleUhfController
import dev.duma.android.nfctorfid.uhf.ChainwayUsbUhfController
import dev.duma.android.nfctorfid.uhf.ReaderBackend
import dev.duma.android.nfctorfid.uhf.SunmiUhfController
import dev.duma.android.nfctorfid.uhf.UhfController
import dev.duma.android.nfctorfid.validate.ValidateFragment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), NfcCardReader.Listener {

    /** The active reader; swapped when the backend selection changes. */
    lateinit var uhf: UhfController
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var feedback: FeedbackSignaler
        private set
    lateinit var nfc: NfcCardReader
        private set

    private lateinit var binding: ActivityMainBinding

    private val sunmi by lazy { SunmiUhfController(applicationContext) }
    private val chainwayUsb by lazy { ChainwayUsbUhfController(applicationContext) }
    private val chainwayBle by lazy {
        ChainwayBleUhfController(
            applicationContext,
            deviceMac = { settings.bleMac },
            deviceName = { settings.bleName },
        )
    }

    private var connectJob: Job? = null
    private var observeJob: Job? = null

    private var blePermissionWaiter: CompletableDeferred<Boolean>? = null
    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            blePermissionWaiter?.complete(grants.values.all { it })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        uhf = sunmi
        feedback = FeedbackSignaler(this, binding.flashOverlay)
        nfc = NfcCardReader(this, this)

        reconnectReader()

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_about -> showFragment(TAG_ABOUT) { AboutFragment() }
                R.id.nav_encode -> showFragment(TAG_ENCODE) { EncodeFragment() }
                R.id.nav_scan -> showFragment(TAG_SCAN) { ScanFragment() }
                R.id.nav_validate -> showFragment(TAG_VALIDATE) { ValidateFragment() }
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_about
        }
    }

    /** (Re)selects a reader per settings and connects; called on start and after settings. */
    fun reconnectReader() {
        connectJob?.cancel()
        connectJob = lifecycleScope.launch {
            val previous = uhf
            val selected = selectController()
            if (previous !== selected) previous.disconnect()
            uhf = selected
            selected.connect()
            observeReader(selected)
        }
    }

    private suspend fun selectController(): UhfController = when (settings.backend) {
        ReaderBackend.SUNMI -> sunmi
        ReaderBackend.CHAINWAY_USB -> chainwayUsb
        ReaderBackend.CHAINWAY_BLE -> chainwayBle.also { ensureBlePermissions() }
        ReaderBackend.AUTO -> when {
            // Physically attached USB reader is the strongest signal of intent.
            chainwayUsb.hasDevice() &&
                runCatching { chainwayUsb.awaitReady(PROBE_TIMEOUT_MS) }.isSuccess ->
                chainwayUsb

            runCatching { sunmi.awaitReady(PROBE_TIMEOUT_MS) }.isSuccess -> sunmi

            settings.bleMac != null && ensureBlePermissions() &&
                runCatching { chainwayBle.awaitReady(BLE_PROBE_TIMEOUT_MS) }.isSuccess ->
                chainwayBle

            // Nothing answered — fall back to Sunmi so the status shows "no reader".
            else -> sunmi
        }
    }

    private fun observeReader(reader: UhfController) {
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            launch {
                reader.state.collect { state ->
                    if (state is UhfController.ReaderState.Ready) {
                        reader.applyPower(settings.readPowerDbm)
                    }
                }
            }
            // Chainway handhelds report their trigger through the SDK — route it
            // exactly like the Sunmi trigger key.
            launch {
                reader.triggerEvents.collect { pressed ->
                    activeScanFragment()?.let {
                        if (pressed) it.onTriggerPressed() else it.onTriggerReleased()
                    }
                }
            }
        }
    }

    /** Requests BLE runtime permissions when missing; true when all are granted. */
    private suspend fun ensureBlePermissions(): Boolean {
        val needed = blePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) return true
        val waiter = CompletableDeferred<Boolean>()
        blePermissionWaiter = waiter
        blePermissionLauncher.launch(needed.toTypedArray())
        val granted = waiter.await()
        blePermissionWaiter = null
        return granted
    }

    private fun showFragment(tag: String, create: () -> Fragment) {
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        for (t in listOf(TAG_ABOUT, TAG_ENCODE, TAG_SCAN, TAG_VALIDATE)) {
            fm.findFragmentByTag(t)?.let { if (t != tag) transaction.hide(it) }
        }
        val target = fm.findFragmentByTag(tag)
        if (target == null) {
            transaction.add(R.id.fragment_container, create(), tag)
        } else {
            transaction.show(target)
        }
        transaction.commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            SettingsDialog().show(supportFragmentManager, "settings")
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KEYCODE_RFID_TRIGGER) {
            if (event?.repeatCount == 0) activeScanFragment()?.onTriggerPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KEYCODE_RFID_TRIGGER) {
            activeScanFragment()?.onTriggerReleased()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun activeScanFragment(): ScanFragment? =
        supportFragmentManager.findFragmentByTag(TAG_SCAN)
            ?.takeIf { it.isVisible } as? ScanFragment

    override fun onResume() {
        super.onResume()
        nfc.enable()
    }

    override fun onPause() {
        nfc.disable()
        super.onPause()
    }

    // --- NfcCardReader.Listener (NFC dispatch thread) — route to the visible tab ---

    private fun visibleConsumer(): NfcCardConsumer? =
        supportFragmentManager.fragments
            .firstOrNull { it.isVisible && it is NfcCardConsumer } as? NfcCardConsumer

    override fun onCardRead(uid: ByteArray, originality: OriginalityStatus) {
        visibleConsumer()?.onNfcCard(uid, originality)
    }

    override fun onRandomUid() {
        visibleConsumer()?.onNfcRandomUid()
    }

    override fun onUnsupportedUid(length: Int) {
        visibleConsumer()?.onNfcUnsupportedUid(length)
    }

    override fun onDestroy() {
        // Always disconnect — RFIDManager keeps listeners in a process-wide list,
        // so skipping this on recreation would leak every previous controller.
        uhf.disconnect()
        feedback.release()
        super.onDestroy()
    }

    companion object {
        /** RFID trigger key on Sunmi devices (same code across models when the key is set to RFID). */
        const val KEYCODE_RFID_TRIGGER = 288

        private const val PROBE_TIMEOUT_MS = 3_000L
        private const val BLE_PROBE_TIMEOUT_MS = 20_000L

        fun blePermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        private const val TAG_ABOUT = "about"
        private const val TAG_ENCODE = "encode"
        private const val TAG_SCAN = "scan"
        private const val TAG_VALIDATE = "validate"
    }
}
