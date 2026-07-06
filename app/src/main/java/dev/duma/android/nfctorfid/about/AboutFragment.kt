package dev.duma.android.nfctorfid.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.duma.android.nfctorfid.MainActivity
import dev.duma.android.nfctorfid.R
import dev.duma.android.nfctorfid.databinding.FragmentAboutBinding
import dev.duma.android.nfctorfid.uhf.UhfController
import kotlinx.coroutines.launch

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    private val main get() = requireActivity() as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val versionName = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "?"
        binding.tvVersion.text = getString(R.string.about_version, versionName)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                main.uhf.state.collect { state ->
                    binding.tvReaderStatus.text = when (state) {
                        is UhfController.ReaderState.Disconnected ->
                            getString(R.string.reader_disconnected)
                        is UhfController.ReaderState.Connecting ->
                            getString(R.string.reader_connecting)
                        is UhfController.ReaderState.NoReader ->
                            getString(R.string.reader_none)
                        is UhfController.ReaderState.Ready ->
                            getString(
                                R.string.reader_ready,
                                UhfController.modelName(state.model),
                            )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
