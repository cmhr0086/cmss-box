package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutSimpleSettingsBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.managed.ManagedSubscriptionCoordinator
import io.nekohasekai.sagernet.ui.dialog.SimpleLoadingDialogFragment
import io.nekohasekai.sagernet.ui.dialog.SubscriptionCodeDialogFragment

class SimpleSettingsFragment : Fragment(R.layout.layout_simple_settings) {
    companion object {
        private const val TAG_VERIFY_LOADING = "verify_loading_dialog"
    }

    private var _binding: LayoutSimpleSettingsBinding? = null
    private val binding get() = _binding!!
    private var changeJobActive = false
    private var pendingSubscriptionCode: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutSimpleSettingsBinding.bind(view)

        binding.backButton.isVisible = false
        binding.backButton.setOnClickListener { }
        binding.changeSubscriptionCodeRow.setOnClickListener { showChangeCodeDialog() }
        binding.updateSubscriptionRow.setOnClickListener {
            (activity as? SimpleMainActivity)?.showSubscriptionUpdateDialog()
        }
        parentFragmentManager.setFragmentResultListener(
            SubscriptionCodeDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val code = bundle.getString(SubscriptionCodeDialogFragment.RESULT_CODE).orEmpty()
            pendingSubscriptionCode = code
            findCodeDialog()?.setSubmitting(true)
            verifySubscriptionCode(code)
        }
    }

    private fun showChangeCodeDialog() {
        if (changeJobActive) return
        if (findCodeDialog() != null) return
        SubscriptionCodeDialogFragment.newInstance(pendingSubscriptionCode)
            .show(parentFragmentManager, SubscriptionCodeDialogFragment.TAG)
    }

    private fun verifySubscriptionCode(code: String) {
        if (changeJobActive) return
        changeJobActive = true
        SimpleLoadingDialogFragment.newInstance("正在验证订阅码", "请稍候...")
            .show(parentFragmentManager, TAG_VERIFY_LOADING)
        setRowsEnabled(false)
        runOnDefaultDispatcher {
            val result = runCatching {
                if (ManagedSubscriptionCoordinator.isActivated) {
                    ManagedSubscriptionCoordinator.changeSubscriptionCode(code)
                } else {
                    ManagedSubscriptionCoordinator.activate(code)
                }
            }
            onMainDispatcher {
                changeJobActive = false
                dismissLoadingDialog()
                setRowsEnabled(true)
                result.onSuccess {
                    findCodeDialog()?.dismissAllowingStateLoss()
                    pendingSubscriptionCode = null
                    Toast.makeText(requireContext(), "订阅码已更新", Toast.LENGTH_SHORT).show()
                    (activity as? SimpleMainActivity)?.onManagedSubscriptionChanged()
                }.onFailure { error ->
                    findCodeDialog()?.setSubmitting(false)
                    findCodeDialog()?.showError(error.readableMessage, code)
                    Toast.makeText(requireContext(), error.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setRowsEnabled(enabled: Boolean) {
        binding.changeSubscriptionCodeRow.isEnabled = enabled
        binding.updateSubscriptionRow.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.6f
        binding.changeSubscriptionCodeRow.alpha = alpha
        binding.updateSubscriptionRow.alpha = alpha
        val color = ContextCompat.getColor(requireContext(), R.color.simple_text_secondary)
        binding.changeSubscriptionCodeChevron.setTextColor(color)
        binding.updateSubscriptionChevron.setTextColor(color)
    }

    fun setUpdateRowEnabled(enabled: Boolean) {
        _binding?.updateSubscriptionRow?.isEnabled = enabled
        _binding?.updateSubscriptionRow?.alpha = if (enabled) 1f else 0.6f
    }

    private fun dismissLoadingDialog() {
        (parentFragmentManager.findFragmentByTag(TAG_VERIFY_LOADING) as? SimpleLoadingDialogFragment)
            ?.dismissAllowingStateLoss()
    }

    private fun findCodeDialog(): SubscriptionCodeDialogFragment? {
        return parentFragmentManager.findFragmentByTag(SubscriptionCodeDialogFragment.TAG)
                as? SubscriptionCodeDialogFragment
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
