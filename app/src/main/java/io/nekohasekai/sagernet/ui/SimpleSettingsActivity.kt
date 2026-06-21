package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutSimpleSettingsBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.managed.ManagedSubscriptionCoordinator
import io.nekohasekai.sagernet.ui.dialog.SimpleLoadingDialogFragment
import io.nekohasekai.sagernet.ui.dialog.SubscriptionCodeDialogFragment

class SimpleSettingsActivity : ThemedActivity() {
    companion object {
        const val RESULT_OPEN_UPDATE_DIALOG = "result_open_update_dialog"
        const val RESULT_REFRESH_HOME = "result_refresh_home"
        private const val TAG_VERIFY_LOADING = "verify_loading_dialog"
    }

    private lateinit var binding: LayoutSimpleSettingsBinding
    private var changeJobActive = false
    private var pendingSubscriptionCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutSimpleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.simple_bg_start)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.simple_bg_start)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        binding.backButton.setOnClickListener { finish() }
        binding.changeSubscriptionCodeRow.setOnClickListener { showChangeCodeDialog() }
        binding.updateSubscriptionRow.setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_OPEN_UPDATE_DIALOG, true))
            finish()
        }
        supportFragmentManager.setFragmentResultListener(
            SubscriptionCodeDialogFragment.REQUEST_KEY,
            this
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
            .show(supportFragmentManager, SubscriptionCodeDialogFragment.TAG)
    }

    private fun verifySubscriptionCode(code: String) {
        if (changeJobActive) return
        changeJobActive = true
        SimpleLoadingDialogFragment.newInstance("正在验证订阅码", "请稍候...")
            .show(supportFragmentManager, TAG_VERIFY_LOADING)
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
                    Toast.makeText(this@SimpleSettingsActivity, "订阅码已更新", Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_REFRESH_HOME, true))
                    finish()
                }.onFailure { error ->
                    findCodeDialog()?.setSubmitting(false)
                    findCodeDialog()?.showError(error.readableMessage, code)
                    Toast.makeText(this@SimpleSettingsActivity, error.readableMessage, Toast.LENGTH_LONG).show()
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
        val color = ContextCompat.getColor(this, R.color.simple_text_secondary)
        binding.changeSubscriptionCodeChevron.setTextColor(color)
        binding.updateSubscriptionChevron.setTextColor(color)
    }

    private fun dismissLoadingDialog() {
        (supportFragmentManager.findFragmentByTag(TAG_VERIFY_LOADING) as? SimpleLoadingDialogFragment)
            ?.dismissAllowingStateLoss()
    }

    private fun findCodeDialog(): SubscriptionCodeDialogFragment? {
        return supportFragmentManager.findFragmentByTag(SubscriptionCodeDialogFragment.TAG)
                as? SubscriptionCodeDialogFragment
    }
}
