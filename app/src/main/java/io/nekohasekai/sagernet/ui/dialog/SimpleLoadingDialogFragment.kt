package io.nekohasekai.sagernet.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import io.nekohasekai.sagernet.databinding.DialogSimpleLoadingBinding

class SimpleLoadingDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"

        fun newInstance(title: String, message: String) = SimpleLoadingDialogFragment().apply {
            arguments = bundleOf(
                ARG_TITLE to title,
                ARG_MESSAGE to message
            )
        }
    }

    private var _binding: DialogSimpleLoadingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), io.nekohasekai.sagernet.R.style.Theme_CMSS_SimpleDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = DialogSimpleLoadingBinding.inflate(inflater, container, false).also {
        _binding = it
    }.root

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.loadingTitle.text = requireArguments().getString(ARG_TITLE).orEmpty()
        binding.loadingMessage.text = requireArguments().getString(ARG_MESSAGE).orEmpty()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
