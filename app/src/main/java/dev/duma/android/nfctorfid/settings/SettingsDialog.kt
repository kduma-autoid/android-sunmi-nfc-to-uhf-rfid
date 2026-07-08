package dev.duma.android.nfctorfid.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import dev.duma.android.nfctorfid.AppSettings
import dev.duma.android.nfctorfid.MainActivity
import dev.duma.android.nfctorfid.R
import dev.duma.android.nfctorfid.databinding.DialogSettingsBinding
import dev.duma.android.nfctorfid.uhf.InventoryMode
import dev.duma.android.nfctorfid.uhf.ReaderBackend
import kotlinx.coroutines.launch

class SettingsDialog : DialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private val main get() = requireActivity() as MainActivity

    private var bleDeviceChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_NFCToRFID_FullScreenDialog)
        parentFragmentManager.setFragmentResultListener(
            BleDevicePickerDialog.RESULT_KEY,
            this,
        ) { _, result ->
            val settings = main.settings
            settings.bleMac = result.getString(BleDevicePickerDialog.KEY_MAC)
            settings.bleName = result.getString(BleDevicePickerDialog.KEY_NAME)
            bleDeviceChanged = true
            if (_binding != null) updateBleLabel()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val settings = main.settings

        binding.btnClose.setOnClickListener { dismiss() }

        binding.groupBackend.check(
            when (settings.backend) {
                ReaderBackend.AUTO -> R.id.radio_backend_auto
                ReaderBackend.SUNMI -> R.id.radio_backend_sunmi
                ReaderBackend.CHAINWAY_USB -> R.id.radio_backend_usb
                ReaderBackend.CHAINWAY_BLE -> R.id.radio_backend_ble
            }
        )
        updateBleLabel()
        binding.btnBleChoose.setOnClickListener {
            BleDevicePickerDialog().show(parentFragmentManager, "blePicker")
        }

        binding.groupInventoryMode.check(
            when (settings.inventoryMode) {
                InventoryMode.HIGH_SPEED -> R.id.radio_mode_high_speed
                InventoryMode.BALANCE -> R.id.radio_mode_balance
                InventoryMode.TRAVERSAL -> R.id.radio_mode_traversal
            }
        )

        binding.inputAccessPwd.setText(settings.accessPasswordHex)
        binding.inputKillPwd.setText(settings.killPasswordHex)
        binding.switchLock.isChecked = settings.lockEnabled
        binding.sliderScanSeconds.valueFrom = AppSettings.MIN_SCAN_SECONDS.toFloat()
        binding.sliderScanSeconds.valueTo = AppSettings.MAX_SCAN_SECONDS.toFloat()
        binding.sliderScanSeconds.value = settings.scanSeconds.toFloat()
        binding.sliderReadPower.valueFrom = AppSettings.MIN_POWER_DBM.toFloat()
        binding.sliderReadPower.valueTo = AppSettings.MAX_POWER_DBM.toFloat()
        binding.sliderReadPower.value = settings.readPowerDbm.toFloat()
        binding.sliderWritePower.valueFrom = AppSettings.MIN_POWER_DBM.toFloat()
        binding.sliderWritePower.valueTo = AppSettings.MAX_POWER_DBM.toFloat()
        binding.sliderWritePower.value = settings.writePowerDbm.toFloat()

        updateSliderLabels()
        binding.sliderScanSeconds.addOnChangeListener { _, _, _ -> updateSliderLabels() }
        binding.sliderReadPower.addOnChangeListener { _, _, _ -> updateSliderLabels() }
        binding.sliderWritePower.addOnChangeListener { _, _, _ -> updateSliderLabels() }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun updateSliderLabels() {
        binding.tvScanSecondsLabel.text =
            getString(R.string.settings_scan_seconds, binding.sliderScanSeconds.value.toInt())
        binding.tvReadPowerLabel.text =
            getString(R.string.settings_read_power, binding.sliderReadPower.value.toInt())
        binding.tvWritePowerLabel.text =
            getString(R.string.settings_write_power, binding.sliderWritePower.value.toInt())
    }

    private fun save() {
        val settings = main.settings
        val accessHex = binding.inputAccessPwd.text?.toString()?.trim().orEmpty().uppercase()
        val killHex = binding.inputKillPwd.text?.toString()?.trim().orEmpty().uppercase()
        val lockEnabled = binding.switchLock.isChecked

        for ((hex, field) in listOf(accessHex to binding.layoutAccessPwd, killHex to binding.layoutKillPwd)) {
            field.error = if (hex.isNotEmpty() && !AppSettings.isValidPasswordHex(hex)) {
                getString(R.string.settings_password_invalid)
            } else {
                null
            }
        }
        if (binding.layoutAccessPwd.error != null || binding.layoutKillPwd.error != null) return

        if (lockEnabled &&
            (!AppSettings.isValidPasswordHex(accessHex) || accessHex == ZERO_HEX ||
                !AppSettings.isValidPasswordHex(killHex) || killHex == ZERO_HEX)
        ) {
            Toast.makeText(requireContext(), R.string.settings_lock_needs_passwords, Toast.LENGTH_LONG).show()
            return
        }

        settings.accessPasswordHex = accessHex
        settings.killPasswordHex = killHex
        settings.lockEnabled = lockEnabled
        settings.scanSeconds = binding.sliderScanSeconds.value.toInt()
        settings.readPowerDbm = binding.sliderReadPower.value.toInt()
        settings.writePowerDbm = binding.sliderWritePower.value.toInt()
        settings.inventoryMode = when (binding.groupInventoryMode.checkedRadioButtonId) {
            R.id.radio_mode_balance -> InventoryMode.BALANCE
            R.id.radio_mode_traversal -> InventoryMode.TRAVERSAL
            else -> InventoryMode.HIGH_SPEED
        }

        val newBackend = when (binding.groupBackend.checkedRadioButtonId) {
            R.id.radio_backend_sunmi -> ReaderBackend.SUNMI
            R.id.radio_backend_usb -> ReaderBackend.CHAINWAY_USB
            R.id.radio_backend_ble -> ReaderBackend.CHAINWAY_BLE
            else -> ReaderBackend.AUTO
        }
        val backendChanged = newBackend != settings.backend || bleDeviceChanged
        settings.backend = newBackend

        // A backend/device change needs a fresh connection; otherwise just re-apply power.
        if (backendChanged) {
            main.reconnectReader()
        } else {
            // The inventory mode is applied by the Scan tab when a scan starts.
            main.lifecycleScope.launch { main.uhf.applyPower(settings.readPowerDbm) }
        }
        dismiss()
    }

    private fun updateBleLabel() {
        val settings = main.settings
        val mac = settings.bleMac
        binding.tvBleDevice.text = when {
            mac == null -> getString(R.string.settings_ble_none)
            settings.bleName.isNullOrBlank() -> mac
            else -> "${settings.bleName} ($mac)"
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val ZERO_HEX = "00000000"
    }
}
