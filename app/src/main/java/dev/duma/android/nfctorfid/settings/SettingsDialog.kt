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
import kotlinx.coroutines.launch

class SettingsDialog : DialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private val main get() = requireActivity() as MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_NFCToRFID_FullScreenDialog)
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

        main.lifecycleScope.launch { main.uhf.applyPower(settings.readPowerDbm) }
        dismiss()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val ZERO_HEX = "00000000"
    }
}
