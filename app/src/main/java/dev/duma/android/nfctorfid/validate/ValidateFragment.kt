package dev.duma.android.nfctorfid.validate

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.duma.android.nfctorfid.MainActivity
import dev.duma.android.nfctorfid.R
import dev.duma.android.nfctorfid.databinding.FragmentValidateBinding
import dev.duma.android.nfctorfid.epc.EpcCodec
import dev.duma.android.nfctorfid.epc.toColonHex
import dev.duma.android.nfctorfid.epc.toHex
import dev.duma.android.nfctorfid.nfc.NfcCardConsumer
import dev.duma.android.nfctorfid.nfc.OriginalityStatus
import dev.duma.android.nfctorfid.uhf.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-by-one validation: tap a card, get a big PAIRED / TAG MISSING verdict. */
class ValidateFragment : Fragment(), NfcCardConsumer {

    private sealed class State {
        data object Idle : State()
        data class Searching(val uid: ByteArray) : State()
        data class Matched(val uid: ByteArray, val rssiDbm: Int?) : State()
        data class NotFound(val uid: ByteArray, val message: String?) : State()
    }

    private var _binding: FragmentValidateBinding? = null
    private val binding get() = _binding!!

    private val main get() = requireActivity() as MainActivity

    private var state: State = State.Idle
    private var originality: OriginalityStatus? = null
    private var job: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentValidateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        render()
    }

    override fun onNfcCard(uid: ByteArray, originality: OriginalityStatus) {
        view?.post {
            if (isHidden || _binding == null) return@post
            this.originality = originality
            main.feedback.acknowledge()
            startValidation(uid)
        }
    }

    private fun startValidation(uid: ByteArray) {
        job?.cancel()
        setState(State.Searching(uid))
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                main.uhf.awaitReady()
                val expectedEpc = EpcCodec.encodeEpc(uid).toHex()
                val deadline =
                    SystemClock.elapsedRealtime() + main.settings.scanSeconds * 1_000L
                var rssi: Int? = null
                var found = false
                while (!found && isActive && SystemClock.elapsedRealtime() < deadline) {
                    val tag = main.uhf.scanTags(SCAN_WINDOW_MS)[expectedEpc]
                    if (tag != null) {
                        found = true
                        rssi = tag.rssiDbm
                    }
                }
                if (found) {
                    main.feedback.success()
                    setState(State.Matched(uid, rssi))
                } else {
                    main.feedback.error()
                    setState(State.NotFound(uid, null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                main.feedback.error()
                setState(State.NotFound(uid, e.toUserMessage(requireContext())))
            }
        }
    }

    private fun setState(newState: State) {
        state = newState
        render()
    }

    private fun render() {
        val b = _binding ?: return
        val s = state

        b.progressValidate.isVisible = s is State.Searching

        b.tvValidateStatus.text = when (s) {
            is State.Idle -> getString(R.string.validate_idle)
            is State.Searching -> getString(R.string.validate_searching)
            is State.Matched -> getString(R.string.validate_matched)
            is State.NotFound -> getString(R.string.validate_not_found)
        }
        b.tvValidateStatus.setTextColor(
            requireContext().getColor(
                when (s) {
                    is State.Matched -> R.color.success
                    is State.NotFound -> R.color.error
                    else -> R.color.neutral
                },
            ),
        )

        val uid = when (s) {
            is State.Searching -> s.uid
            is State.Matched -> s.uid
            is State.NotFound -> s.uid
            else -> null
        }
        b.tvValidateUid.isVisible = uid != null
        b.tvValidateUid.text = uid?.let { getString(R.string.nfc_card_uid, it.toColonHex()) } ?: ""

        b.tvValidateDetail.isVisible = s is State.Matched || s is State.NotFound
        b.tvValidateDetail.text = when (s) {
            is State.Matched -> getString(
                R.string.validate_matched_detail,
                s.rssiDbm?.let { "$it dBm" } ?: "?",
            )
            is State.NotFound -> s.message ?: getString(R.string.validate_not_found_detail)
            else -> ""
        }

        val orig = originality
        b.tvValidateOriginality.isVisible = orig != null && uid != null
        when (orig) {
            is OriginalityStatus.Verified -> {
                b.tvValidateOriginality.text = getString(R.string.originality_verified, orig.chipName)
                b.tvValidateOriginality.setTextColor(requireContext().getColor(R.color.success))
            }
            is OriginalityStatus.Invalid -> {
                b.tvValidateOriginality.text = getString(R.string.originality_invalid)
                b.tvValidateOriginality.setTextColor(requireContext().getColor(R.color.error))
            }
            is OriginalityStatus.NotSupported -> {
                b.tvValidateOriginality.text = getString(R.string.originality_not_supported)
                b.tvValidateOriginality.setTextColor(requireContext().getColor(R.color.neutral))
            }
            is OriginalityStatus.ReadError -> {
                b.tvValidateOriginality.text = getString(R.string.originality_read_error)
                b.tvValidateOriginality.setTextColor(requireContext().getColor(R.color.warning))
            }
            null -> Unit
        }
    }

    private fun reset() {
        job?.cancel()
        originality = null
        setState(State.Idle)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) reset()
    }

    override fun onPause() {
        reset()
        super.onPause()
    }

    override fun onDestroyView() {
        job?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val SCAN_WINDOW_MS = 600L
    }
}
