package dev.duma.android.nfctorfid.scan

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.duma.android.nfctorfid.MainActivity
import dev.duma.android.nfctorfid.R
import dev.duma.android.nfctorfid.databinding.FragmentScanBinding
import dev.duma.android.nfctorfid.epc.EpcCodec
import dev.duma.android.nfctorfid.epc.colonizeHex
import dev.duma.android.nfctorfid.epc.toHex
import dev.duma.android.nfctorfid.nfc.NfcCardConsumer
import dev.duma.android.nfctorfid.nfc.OriginalityStatus
import dev.duma.android.nfctorfid.uhf.InventoryMode
import dev.duma.android.nfctorfid.uhf.TagInfo
import dev.duma.android.nfctorfid.uhf.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanFragment : Fragment(), NfcCardConsumer {

    data class ScannedTag(
        val uidHex: String,
        val epcHex: String,
        val rssiDbm: Int?,
        val readCount: Int,
        /** Tag EPC read over UHF. */
        val seenUhf: Boolean = false,
        /** Matching card tapped on the NFC reader. */
        val seenNfc: Boolean = false,
    )

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private val main get() = requireActivity() as MainActivity

    private val adapter = TagListAdapter()

    /** Cumulative between scans, keyed by EPC. */
    private val ourTags = LinkedHashMap<String, ScannedTag>()
    private val foreignEpcs = HashSet<String>()

    private var scanJob: Job? = null
    private val scanning get() = scanJob?.isActive == true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.listTags.layoutManager = LinearLayoutManager(requireContext())
        binding.listTags.adapter = adapter

        binding.btnScanToggle.setOnClickListener { if (scanning) stopScan() else startScan() }
        binding.btnClear.setOnClickListener {
            ourTags.clear()
            foreignEpcs.clear()
            updateList()
        }
        binding.btnShare.setOnClickListener { shareUids() }

        updateList()
        updateScanButton()
    }

    fun onTriggerPressed() {
        if (!scanning) startScan()
    }

    fun onTriggerReleased() {
        stopScan()
    }

    private fun startScan() {
        if (scanning) return
        scanJob = viewLifecycleOwner.lifecycleScope.launch {
            updateScanButton()
            try {
                main.uhf.awaitReady()
                // The configured mode applies only to this tab; encoding and
                // validation always scan in high speed.
                main.uhf.applyInventoryMode(main.settings.inventoryMode)
                while (isActive) {
                    val tags = main.uhf.scanTags(SCAN_WINDOW_MS)
                    merge(tags.values)
                    updateList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.let { Toast.makeText(it, e.toUserMessage(it), Toast.LENGTH_SHORT).show() }
            } finally {
                withContext(NonCancellable) {
                    runCatching { main.uhf.applyInventoryMode(InventoryMode.HIGH_SPEED) }
                }
                updateScanButton()
            }
        }
        updateScanButton()
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        updateScanButton()
    }

    private fun merge(tags: Collection<TagInfo>) {
        for (tag in tags) {
            val uidHex = EpcCodec.decodeUidHex(tag.epcHex)
            if (uidHex == null) {
                foreignEpcs.add(tag.epcHex)
                continue
            }
            val old = ourTags[tag.epcHex]
            ourTags[tag.epcHex] = ScannedTag(
                uidHex = uidHex,
                epcHex = tag.epcHex,
                rssiDbm = tag.rssiDbm ?: old?.rssiDbm,
                readCount = (old?.readCount ?: 0) + tag.readCount,
                seenUhf = true,
                seenNfc = old?.seenNfc == true,
            )
            // A red (card-only) entry just found its tag — signal the pairing.
            if (old != null && old.seenNfc && !old.seenUhf) main.feedback.success()
        }
    }

    // --- NfcCardConsumer (NFC dispatch thread): validation by pairing ---

    override fun onNfcCard(uid: ByteArray, originality: OriginalityStatus) {
        view?.post {
            if (isHidden || _binding == null) return@post
            handleNfcCard(uid)
        }
    }

    override fun onNfcRandomUid() {
        view?.post {
            context?.let { Toast.makeText(it, R.string.nfc_random_uid, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onNfcUnsupportedUid(length: Int) {
        view?.post {
            context?.let {
                Toast.makeText(
                    it,
                    it.resources.getQuantityString(R.plurals.nfc_unsupported_uid, length, length),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun handleNfcCard(uid: ByteArray) {
        val epcHex = EpcCodec.encodeEpc(uid).toHex()
        val existing = ourTags[epcHex]
        if (existing?.seenUhf == true) {
            if (!existing.seenNfc) ourTags[epcHex] = existing.copy(seenNfc = true)
            main.feedback.success()
            updateList()
            return
        }
        // Not seen over UHF yet — a red entry pinned on top; it turns green (with a
        // success signal) once a trigger/Start scan reads the tag. No UHF scan is
        // started here.
        main.feedback.acknowledge()
        ourTags[epcHex] = ScannedTag(
            uidHex = uid.toHex(),
            epcHex = epcHex,
            rssiDbm = null,
            readCount = 0,
            seenUhf = false,
            seenNfc = true,
        )
        updateList()
    }

    private fun updateList() {
        val b = _binding ?: return
        val sorted = ourTags.values.sortedWith(
            // Unpaired cards (red) pinned on top, the rest by signal strength.
            compareByDescending<ScannedTag> { it.seenNfc && !it.seenUhf }
                .thenByDescending { it.rssiDbm ?: Int.MIN_VALUE }
                .thenBy { it.uidHex },
        )
        adapter.submit(sorted)
        b.tvCounts.text = getString(R.string.scan_counts, ourTags.size, foreignEpcs.size)
        val paired = ourTags.values.count { it.seenNfc && it.seenUhf }
        val missing = ourTags.values.count { it.seenNfc && !it.seenUhf }
        b.tvValidation.isVisible = paired + missing > 0
        b.tvValidation.text = getString(R.string.scan_validation_counts, paired, missing)
        b.tvEmpty.isVisible = sorted.isEmpty()
    }

    private fun updateScanButton() {
        val b = _binding ?: return
        b.btnScanToggle.text =
            getString(if (scanning) R.string.scan_stop else R.string.scan_start)
        b.progressScan.isVisible = scanning
    }

    private fun shareUids() {
        if (ourTags.isEmpty()) {
            Toast.makeText(requireContext(), R.string.scan_nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val text = ourTags.values
            .sortedBy { it.uidHex }
            .joinToString("\n") { it.uidHex.colonizeHex() }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.scan_share_title)))
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopScan()
    }

    override fun onPause() {
        stopScan()
        super.onPause()
    }

    override fun onDestroyView() {
        stopScan()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val SCAN_WINDOW_MS = 600L
    }
}
