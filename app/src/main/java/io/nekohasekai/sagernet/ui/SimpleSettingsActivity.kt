package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.DialogSubscriptionCodeInputBinding
import io.nekohasekai.sagernet.databinding.LayoutSimpleSettingsBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.managed.ManagedSubscriptionCoordinator

class SimpleSettingsActivity : ThemedActivity() {
    companion object {
        const val RESULT_OPEN_UPDATE_DIALOG = "result_open_update_dialog"
        const val RESULT_REFRESH_HOME = "result_refresh_home"
    }

    private lateinit var binding: LayoutSimpleSettingsBinding
    private var changeJobActive = false

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
    }

    private fun showChangeCodeDialog() {
        if (changeJobActive) return
        val dialogBinding = DialogSubscriptionCodeInputBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_CMSS_SimpleDialog)
            .setTitle("更换订阅码")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val code = dialogBinding.subscriptionCodeInput.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) {
                    dialogBinding.subscriptionCodeInput.error = "请输入订阅码"
                    return@setOnClickListener
                }
                dialogBinding.subscriptionCodeInput.error = null
                verifySubscriptionCode(code, dialog)
            }
        }
        dialog.show()
    }

    private fun verifySubscriptionCode(code: String, inputDialog: AlertDialog) {
        if (changeJobActive) return
        changeJobActive = true
        val progress = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_CMSS_SimpleDialog)
            .setTitle("正在验证订阅码")
            .setMessage("请稍候...")
            .setCancelable(false)
            .create()
        progress.show()
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
                progress.dismiss()
                setRowsEnabled(true)
                result.onSuccess {
                    inputDialog.dismiss()
                    Toast.makeText(this@SimpleSettingsActivity, "订阅码已更新", Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_REFRESH_HOME, true))
                    finish()
                }.onFailure { error ->
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
}
