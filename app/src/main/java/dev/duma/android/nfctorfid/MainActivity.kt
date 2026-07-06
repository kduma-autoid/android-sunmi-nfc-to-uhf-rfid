package dev.duma.android.nfctorfid

import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.duma.android.nfctorfid.about.AboutFragment
import dev.duma.android.nfctorfid.databinding.ActivityMainBinding
import dev.duma.android.nfctorfid.encode.EncodeFragment
import dev.duma.android.nfctorfid.nfc.NfcCardConsumer
import dev.duma.android.nfctorfid.nfc.NfcCardReader
import dev.duma.android.nfctorfid.nfc.OriginalityStatus
import dev.duma.android.nfctorfid.scan.ScanFragment
import dev.duma.android.nfctorfid.settings.SettingsDialog
import dev.duma.android.nfctorfid.uhf.UhfController
import dev.duma.android.nfctorfid.validate.ValidateFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), NfcCardReader.Listener {

    lateinit var uhf: UhfController
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var feedback: FeedbackSignaler
        private set
    lateinit var nfc: NfcCardReader
        private set

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        uhf = UhfController(applicationContext)
        feedback = FeedbackSignaler(this, binding.flashOverlay)
        nfc = NfcCardReader(this, this)

        uhf.connect()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                uhf.state.collect { state ->
                    if (state is UhfController.ReaderState.Ready) {
                        uhf.applyPower(settings.readPowerDbm)
                    }
                }
            }
        }

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

        private const val TAG_ABOUT = "about"
        private const val TAG_ENCODE = "encode"
        private const val TAG_SCAN = "scan"
        private const val TAG_VALIDATE = "validate"
    }
}
