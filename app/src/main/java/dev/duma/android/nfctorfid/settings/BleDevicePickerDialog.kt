package dev.duma.android.nfctorfid.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rscja.deviceapi.RFIDWithUHFBLE
import dev.duma.android.nfctorfid.MainActivity
import dev.duma.android.nfctorfid.R
import dev.duma.android.nfctorfid.databinding.DialogBlePickerBinding
import dev.duma.android.nfctorfid.databinding.ItemBleDeviceBinding
import dev.duma.android.nfctorfid.uhf.BleSdk

/**
 * Scans for Chainway BLE readers; the chosen device is posted as a fragment result.
 * Like the vendor app, unnamed advertisements are filtered out and the list is
 * sorted by signal strength.
 */
class BleDevicePickerDialog : DialogFragment() {

    private data class Row(val name: String?, val mac: String, val rssi: Int)

    private var binding: DialogBlePickerBinding? = null
    private val devices = LinkedHashMap<String, Row>()
    private var scanning = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                startScan()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.ble_permission_missing,
                    Toast.LENGTH_SHORT,
                ).show()
                dismiss()
            }
        }

    private val adapter = object : RecyclerView.Adapter<Holder>() {

        var rows: List<Row> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                ItemBleDeviceBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.binding.tvBleName.text = row.name
            holder.binding.tvBleDetails.text = "${row.mac} · ${row.rssi} dBm"
            holder.binding.root.setOnClickListener {
                parentFragmentManager.setFragmentResult(
                    RESULT_KEY,
                    bundleOf(KEY_MAC to row.mac, KEY_NAME to row.name),
                )
                dismiss()
            }
        }
    }

    private class Holder(val binding: ItemBleDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val b = DialogBlePickerBinding.inflate(layoutInflater)
        binding = b
        b.listBleDevices.layoutManager = LinearLayoutManager(requireContext())
        b.listBleDevices.adapter = adapter
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ble_picker_title)
            .setView(b.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val missing = MainActivity.blePermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission") // checked in onStart before startScan()
    private fun startScan() {
        if (scanning) return
        scanning = true
        BleSdk.ensureInit(requireContext())
        RFIDWithUHFBLE.getInstance().startScanBTDevices { device, rssi, _ ->
            val mac = device?.address ?: return@startScanBTDevices
            val name = runCatching { device.name }.getOrNull()
            binding?.root?.post {
                // Keep a known name if a later advertisement omits it.
                val effectiveName = name?.takeIf { it.isNotBlank() } ?: devices[mac]?.name
                // Like the vendor app: unnamed advertisements (phones, beacons…)
                // are noise — only named devices make the list.
                if (effectiveName.isNullOrBlank()) return@post
                devices[mac] = Row(name = effectiveName, mac = mac, rssi = rssi)
                refresh()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        val b = binding ?: return
        adapter.rows = devices.values.sortedByDescending { it.rssi }
        adapter.notifyDataSetChanged()
        b.tvBleState.text = getString(
            if (adapter.rows.isEmpty()) R.string.ble_picker_empty else R.string.ble_picker_scanning
        )
        b.progressBle.isVisible = true
    }

    override fun onStop() {
        if (scanning) {
            scanning = false
            runCatching { RFIDWithUHFBLE.getInstance().stopScanBTDevices() }
        }
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "bleDevice"
        const val KEY_MAC = "mac"
        const val KEY_NAME = "name"
    }
}
