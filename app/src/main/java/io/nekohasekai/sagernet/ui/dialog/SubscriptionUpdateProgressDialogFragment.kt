package io.nekohasekai.sagernet.ui.dialog

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.DialogSimpleSubscriptionUpdateBinding

class SubscriptionUpdateProgressDialogFragment : DialogFragment() {
    companion object {
        const val TAG = "subscription_update_progress_dialog"
    }

    enum class StepState {
        Pending,
        Running,
        Done,
        Failed
    }

    private var _binding: DialogSimpleSubscriptionUpdateBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.Theme_CMSS_SimpleDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
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
    ) = DialogSimpleSubscriptionUpdateBinding.inflate(inflater, container, false).also {
        _binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.closeButton.setOnClickListener { dismissAllowingStateLoss() }
        setSummary("准备更新...")
        updateRemoteStep(StepState.Pending)
        updateLocalStep(StepState.Pending)
        setClosable(false)
    }

    fun setSummary(text: String) {
        _binding?.summaryText?.text = text
    }

    fun updateRemoteStep(state: StepState, message: String? = null) {
        _binding?.let {
            applyStepState(
                dot = it.remoteStepDot,
                indicator = it.remoteStepIndicator,
                status = it.remoteStepStatus,
                line = it.stepConnector,
                state = state,
                message = message
            )
        }
    }

    fun updateLocalStep(state: StepState, message: String? = null) {
        _binding?.let {
            applyStepState(
                dot = it.localStepDot,
                indicator = it.localStepIndicator,
                status = it.localStepStatus,
                line = null,
                state = state,
                message = message
            )
        }
    }

    fun setClosable(closable: Boolean) {
        dialog?.setCancelable(closable)
        _binding?.closeButton?.isEnabled = closable
        _binding?.closeButton?.alpha = if (closable) 1f else 0.55f
    }

    private fun applyStepState(
        dot: TextView,
        indicator: ProgressBar,
        status: TextView,
        line: View?,
        state: StepState,
        message: String?
    ) {
        @ColorRes val colorRes = when (state) {
            StepState.Pending -> R.color.simple_card_stroke
            StepState.Running -> R.color.simple_accent_green
            StepState.Done -> R.color.simple_accent_green
            StepState.Failed -> R.color.material_red_500
        }
        @ColorRes val textColorRes = when (state) {
            StepState.Pending -> R.color.simple_text_secondary
            StepState.Running -> R.color.simple_text_primary
            StepState.Done -> R.color.simple_text_primary
            StepState.Failed -> R.color.material_red_500
        }
        val color = ContextCompat.getColor(requireContext(), colorRes)
        dot.backgroundTintList = ColorStateList.valueOf(color)
        dot.text = when (state) {
            StepState.Done -> "✓"
            StepState.Failed -> "!"
            else -> ""
        }
        indicator.visibility = if (state == StepState.Running) View.VISIBLE else View.GONE
        indicator.indeterminateTintList = ColorStateList.valueOf(color)
        status.text = message ?: when (state) {
            StepState.Pending -> "等待中"
            StepState.Running -> "进行中..."
            StepState.Done -> "已完成"
            StepState.Failed -> "失败"
        }
        status.setTextColor(ContextCompat.getColor(requireContext(), textColorRes))
        line?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (state == StepState.Done) R.color.simple_accent_green else R.color.simple_card_stroke
            )
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
