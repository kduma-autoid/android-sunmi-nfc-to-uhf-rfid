package dev.duma.android.nfctorfid.encode

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.duma.android.nfctorfid.MainActivity
import dev.duma.android.nfctorfid.R
import dev.duma.android.nfctorfid.databinding.FragmentEncodeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.duma.android.nfctorfid.epc.EpcCodec
import dev.duma.android.nfctorfid.epc.colonizeHex
import dev.duma.android.nfctorfid.epc.toColonHex
import dev.duma.android.nfctorfid.epc.toHex
import dev.duma.android.nfctorfid.nfc.NfcCardConsumer
import dev.duma.android.nfctorfid.nfc.OriginalityStatus
import dev.duma.android.nfctorfid.uhf.TagInfo
import dev.duma.android.nfctorfid.uhf.WriteStep
import dev.duma.android.nfctorfid.uhf.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EncodeFragment : Fragment(), NfcCardConsumer {

    private sealed class State {
        data object WaitNfc : State()
        data class Scanning(val uid: ByteArray, val found: Int) : State()
        data class NoTag(val uid: ByteArray) : State()
        data class MultipleTags(val uid: ByteArray, val count: Int) : State()
        data class Confirm(val uid: ByteArray, val tag: TagInfo, val overwriteUidHex: String?) : State()
        data class AlreadyEncoded(val uid: ByteArray, val tag: TagInfo) : State()
        data class Writing(val uid: ByteArray, val step: WriteStep) : State()
        data class Success(val uidHex: String, val epcHex: String) : State()
        data class Failure(val message: String, val uid: ByteArray?) : State()
    }

    private var _binding: FragmentEncodeBinding? = null
    private val binding get() = _binding!!

    private val main get() = requireActivity() as MainActivity

    private var state: State = State.WaitNfc
    private var job: Job? = null

    /** Originality status of the most recently scanned card. */
    private var originality: OriginalityStatus? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEncodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.switchContinuous.isChecked = main.settings.continuousMode
        binding.switchContinuous.setOnCheckedChangeListener { _, checked ->
            main.settings.continuousMode = checked
        }

        binding.btnWrite.setOnClickListener {
            (state as? State.Confirm)?.let { startWrite(it.uid, it.tag) }
        }
        binding.btnRescan.setOnClickListener {
            when (val s = state) {
                is State.Confirm -> startUhfScan(s.uid)
                is State.NoTag -> startUhfScan(s.uid)
                is State.MultipleTags -> startUhfScan(s.uid)
                is State.Failure -> s.uid?.let { startUhfScan(it) }
                else -> Unit
            }
        }
        binding.btnWriteAnyway.setOnClickListener {
            (state as? State.AlreadyEncoded)?.let { startWrite(it.uid, it.tag) }
        }
        binding.btnReset.setOnClickListener { resetToWaitNfc() }

        render()
    }

    override fun onResume() {
        super.onResume()
        updateNfcStatus()
    }

    override fun onPause() {
        interruptWork()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) interruptWork()
        if (isResumed && !hidden) updateNfcStatus()
    }

    /**
     * Stops in-flight UHF work when the tab is hidden or the app is paused, so
     * the shared reader is free for the Scan tab and no result lands unseen.
     */
    private fun interruptWork() {
        when (val s = state) {
            is State.Scanning -> {
                job?.cancel()
                setState(State.WaitNfc)
            }
            is State.Writing -> {
                job?.cancel()
                setState(State.Failure(getString(R.string.error_interrupted), s.uid))
            }
            else -> Unit
        }
    }

    private fun updateNfcStatus() {
        if (_binding == null) return
        binding.tvNfcStatus.text = when {
            !main.nfc.isAvailable -> getString(R.string.nfc_unavailable)
            !main.nfc.isEnabled -> getString(R.string.nfc_disabled)
            else -> getString(R.string.nfc_wait_for_card)
        }
    }

    // --- NfcCardConsumer (NFC dispatch thread) ---

    override fun onNfcCard(uid: ByteArray, originality: OriginalityStatus) {
        view?.post {
            if (isHidden || _binding == null) return@post
            if (state is State.WaitNfc || state is State.Success ||
                state is State.AlreadyEncoded || state is State.Failure
            ) {
                this.originality = originality
                if (originality is OriginalityStatus.Invalid) {
                    confirmCounterfeit(uid)
                } else {
                    main.feedback.acknowledge()
                    startUhfScan(uid)
                }
            }
        }
    }

    /** Signature read but not matching any NXP key — pause and ask the user. */
    private fun confirmCounterfeit(uid: ByteArray) {
        main.feedback.error()
        render()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.originality_dialog_title)
            .setMessage(R.string.originality_dialog_message)
            .setPositiveButton(R.string.originality_dialog_continue) { _, _ -> startUhfScan(uid) }
            .setNegativeButton(R.string.originality_dialog_cancel) { _, _ -> resetToWaitNfc() }
            .setCancelable(false)
            .show()
    }

    override fun onNfcRandomUid() {
        view?.post {
            context?.let { toast(it.getString(R.string.nfc_random_uid)) }
        }
    }

    override fun onNfcUnsupportedUid(length: Int) {
        view?.post {
            context?.let {
                toast(it.resources.getQuantityString(R.plurals.nfc_unsupported_uid, length, length))
            }
        }
    }

    // --- workflow ---

    private fun startUhfScan(uid: ByteArray) {
        job?.cancel()
        setState(State.Scanning(uid, 0))
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                main.uhf.awaitReady()
                val durationMs = main.settings.scanSeconds * 1_000L
                val tags = main.uhf.scanTags(durationMs) { found ->
                    view?.post {
                        (state as? State.Scanning)?.let {
                            if (it.uid.contentEquals(uid)) setState(State.Scanning(uid, found.size))
                        }
                    }
                }
                onScanFinished(uid, tags.values.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                main.feedback.error()
                setState(State.Failure(e.toUserMessage(requireContext()), uid))
            }
        }
    }

    private fun onScanFinished(uid: ByteArray, tags: List<TagInfo>) {
        when {
            tags.isEmpty() -> {
                main.feedback.error()
                setState(State.NoTag(uid))
            }
            tags.size > 1 -> {
                main.feedback.error()
                setState(State.MultipleTags(uid, tags.size))
            }
            else -> {
                val tag = tags.single()
                val targetEpcHex = EpcCodec.encodeEpc(uid).toHex()
                // EPC comparison only — the PC word is not checked (see writeAndLock).
                if (tag.epcHex == targetEpcHex) {
                    if (main.settings.lockEnabled) {
                        // EPC is right, but a previous run may have failed before the
                        // locks — idempotently (re)write passwords and locks.
                        startWrite(uid, tag, skipEpcWrite = true)
                    } else {
                        main.feedback.success()
                        setState(State.AlreadyEncoded(uid, tag))
                        scheduleContinuousRestart()
                    }
                } else {
                    setState(State.Confirm(uid, tag, EpcCodec.decodeUidHex(tag.epcHex)))
                }
            }
        }
    }

    private fun startWrite(uid: ByteArray, tag: TagInfo, skipEpcWrite: Boolean = false) {
        val settings = main.settings
        val lockEnabled = settings.lockEnabled
        val accessPwd = settings.accessPasswordBytes()
        val killPwd = settings.killPasswordBytes()
        if (lockEnabled && (accessPwd == null || killPwd == null)) {
            main.feedback.error()
            setState(State.Failure(getString(R.string.error_passwords_not_configured), uid))
            return
        }

        job?.cancel()
        setState(State.Writing(uid, WriteStep.TARGETING))
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                main.uhf.writeAndLock(
                    currentEpcHex = tag.epcHex,
                    uid = uid,
                    accessPwd = accessPwd,
                    killPwd = killPwd,
                    lockEnabled = lockEnabled,
                    skipEpcWrite = skipEpcWrite,
                    readPowerDbm = settings.readPowerDbm,
                    writePowerDbm = settings.writePowerDbm,
                ) { step ->
                    view?.post { setState(State.Writing(uid, step)) }
                }
                main.feedback.success()
                setState(
                    if (skipEpcWrite) {
                        State.AlreadyEncoded(uid, tag)
                    } else {
                        State.Success(uid.toHex(), EpcCodec.encodeEpc(uid).toHex())
                    },
                )
                scheduleContinuousRestart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                main.feedback.error()
                setState(State.Failure(e.toUserMessage(requireContext()), uid))
            }
        }
    }

    private fun scheduleContinuousRestart() {
        if (!main.settings.continuousMode) return
        val expected = state
        viewLifecycleOwner.lifecycleScope.launch {
            delay(CONTINUOUS_RESULT_DELAY_MS)
            if (state === expected) resetToWaitNfc()
        }
    }

    private fun resetToWaitNfc() {
        job?.cancel()
        originality = null
        setState(State.WaitNfc)
    }

    // --- rendering ---

    private fun setState(newState: State) {
        state = newState
        render()
    }

    private fun render() {
        val b = _binding ?: return
        val s = state

        // Step 1 — NFC card
        b.tvUid.isVisible = s.uidHex() != null
        b.tvUid.text = s.uidHex()?.let { getString(R.string.nfc_card_uid, it.colonizeHex()) } ?: ""
        if (s is State.WaitNfc) updateNfcStatus()
        val orig = originality
        b.tvOriginality.isVisible = orig != null
        when (orig) {
            is OriginalityStatus.Verified -> {
                b.tvOriginality.text = getString(R.string.originality_verified, orig.chipName)
                b.tvOriginality.setTextColor(requireContext().getColor(R.color.success))
            }
            is OriginalityStatus.Invalid -> {
                b.tvOriginality.text = getString(R.string.originality_invalid)
                b.tvOriginality.setTextColor(requireContext().getColor(R.color.error))
            }
            is OriginalityStatus.NotSupported -> {
                b.tvOriginality.text = getString(R.string.originality_not_supported)
                b.tvOriginality.setTextColor(requireContext().getColor(R.color.neutral))
            }
            is OriginalityStatus.ReadError -> {
                b.tvOriginality.text = getString(R.string.originality_read_error)
                b.tvOriginality.setTextColor(requireContext().getColor(R.color.warning))
            }
            null -> Unit
        }

        // Step 2 — UHF scan
        b.cardUhf.isVisible = s !is State.WaitNfc
        b.progressUhf.isVisible = s is State.Scanning
        b.tvUhfStatus.text = when (s) {
            is State.Scanning -> getString(R.string.uhf_scanning, s.found)
            is State.NoTag -> getString(R.string.uhf_no_tag)
            is State.MultipleTags ->
                resources.getQuantityString(R.plurals.uhf_multiple_tags, s.count, s.count)
            is State.Confirm -> getString(R.string.uhf_one_tag)
            is State.AlreadyEncoded, is State.Success -> getString(R.string.uhf_one_tag)
            is State.Writing -> getString(R.string.uhf_one_tag)
            is State.Failure -> getString(R.string.uhf_failed)
            else -> ""
        }

        // Step 3 — confirm & write
        b.cardConfirm.isVisible = s is State.Confirm || s is State.Writing
        when (s) {
            is State.Confirm -> {
                b.tvCurrentEpc.text = getString(
                    R.string.confirm_current_epc,
                    s.tag.epcHex,
                    s.tag.rssiDbm?.let { "$it dBm" } ?: "?",
                )
                b.tvNewEpc.text = getString(
                    R.string.confirm_new_epc,
                    EpcCodec.encodeEpc(s.uid).toHex(),
                )
                b.tvOverwriteWarning.isVisible = s.overwriteUidHex != null
                b.tvOverwriteWarning.text = s.overwriteUidHex
                    ?.let { getString(R.string.confirm_overwrite_warning, it.colonizeHex()) } ?: ""
                b.btnWrite.isVisible = true
                b.btnWrite.isEnabled = true
                b.tvWriteProgress.isVisible = false
            }
            is State.Writing -> {
                b.btnWrite.isVisible = true
                b.btnWrite.isEnabled = false
                b.tvOverwriteWarning.isVisible = false
                b.tvWriteProgress.isVisible = true
                b.tvWriteProgress.text = getString(
                    when (s.step) {
                        WriteStep.TARGETING -> R.string.step_targeting
                        WriteStep.WRITING_EPC -> R.string.step_writing_epc
                        WriteStep.WRITING_PASSWORDS -> R.string.step_writing_passwords
                        WriteStep.LOCKING -> R.string.step_locking
                        WriteStep.VERIFYING -> R.string.step_verifying
                    },
                )
            }
            else -> Unit
        }

        // Step 4 — result
        b.cardResult.isVisible =
            s is State.Success || s is State.Failure || s is State.AlreadyEncoded
        b.tvResult.text = when (s) {
            is State.Success -> getString(R.string.result_success, s.uidHex.colonizeHex(), s.epcHex)
            is State.AlreadyEncoded ->
                getString(R.string.result_already_encoded, s.uid.toColonHex())
            is State.Failure -> s.message
            else -> ""
        }

        // Buttons
        b.btnWriteAnyway.isVisible = s is State.AlreadyEncoded
        b.btnRescan.isVisible = s is State.Confirm || s is State.NoTag ||
            s is State.MultipleTags || (s is State.Failure && s.uid != null)
        b.btnReset.isVisible = s !is State.WaitNfc && s !is State.Writing
    }

    private fun State.uidHex(): String? = when (this) {
        is State.Scanning -> uid.toHex()
        is State.NoTag -> uid.toHex()
        is State.MultipleTags -> uid.toHex()
        is State.Confirm -> uid.toHex()
        is State.Writing -> uid.toHex()
        is State.AlreadyEncoded -> uid.toHex()
        is State.Success -> uidHex
        is State.Failure -> uid?.toHex()
        else -> null
    }

    private fun toast(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        job?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val CONTINUOUS_RESULT_DELAY_MS = 2_000L
    }
}
