package io.nekohasekai.sagernet.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import io.nekohasekai.sagernet.databinding.DialogSimpleSubscriptionCodeBinding

class SubscriptionCodeDialogFragment : DialogFragment() {
    companion object {
        const val TAG = "subscription_code_dialog"
        const val REQUEST_KEY = "subscription_code_request"
        const val RESULT_CODE = "subscription_code"
        private const val ARG_INITIAL_CODE = "initial_code"

        fun newInstance(initialCode: String? = null) = SubscriptionCodeDialogFragment().apply {
            arguments = bundleOf(ARG_INITIAL_CODE to initialCode)
        }
    }

    private var _binding: DialogSimpleSubscriptionCodeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), io.nekohasekai.sagernet.R.style.Theme_CMSS_SimpleDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = DialogSimpleSubscriptionCodeBinding.inflate(inflater, container, false).also {
        _binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.subscriptionCodeInput.setText(arguments?.getString(ARG_INITIAL_CODE).orEmpty())
        binding.cancelButton.setOnClickListener { dismissAllowingStateLoss() }
        binding.confirmButton.setOnClickListener {
            val code = binding.subscriptionCodeInput.text?.toString()?.trim().orEmpty()
            if (code.isBlank()) {
                binding.subscriptionCodeLayout.error = "请输入订阅码"
                return@setOnClickListener
            }
            binding.subscriptionCodeLayout.error = null
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_CODE to code)
            )
        }
    }

    fun setSubmitting(submitting: Boolean) {
        _binding?.apply {
            subscriptionCodeLayout.isEnabled = !submitting
            cancelButton.isEnabled = !submitting
            confirmButton.isEnabled = !submitting
            confirmButton.text = if (submitting) "验证中..." else "确定"
        }
    }

    fun showError(message: String, code: String? = null) {
        _binding?.apply {
            if (!code.isNullOrBlank()) {
                subscriptionCodeInput.setText(code)
                subscriptionCodeInput.setSelection(subscriptionCodeInput.text?.length ?: 0)
            }
            subscriptionCodeLayout.error = message
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
