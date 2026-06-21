package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutActivationBinding
import io.nekohasekai.sagernet.managed.ManagedApiException
import io.nekohasekai.sagernet.managed.ManagedSubscriptionCoordinator
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class ActivationActivity : ThemedActivity() {
    companion object {
        const val EXTRA_FORCE_INPUT = "force_input"
    }

    private lateinit var binding: LayoutActivationBinding
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.simple_bg_start)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.simple_bg_start)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        binding.activateButton.setOnClickListener { activate() }
        binding.retryButton.setOnClickListener { verifyAndContinue() }
        binding.changeCodeButton.setOnClickListener { switchSubscriptionCode() }
        if (intent.getBooleanExtra(EXTRA_FORCE_INPUT, false)) {
            showActivationForm()
        } else if (ManagedSubscriptionCoordinator.isActivated) {
            verifyAndContinue()
        } else {
            showActivationForm()
        }
    }

    private fun activate() {
        val code = binding.inviteCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            binding.inviteCode.error = "请输入订阅码"
            return
        }
        runTask("正在验证订阅码...") {
            if (ManagedSubscriptionCoordinator.isActivated) {
                ManagedSubscriptionCoordinator.changeSubscriptionCode(code)
            } else {
                ManagedSubscriptionCoordinator.activate(code)
            }
        }
    }

    private fun verifyAndContinue() {
        runTask("正在验证设备并更新节点...") { ManagedSubscriptionCoordinator.refresh() }
    }

    private fun runTask(
        message: String,
        task: suspend () -> ManagedSubscriptionCoordinator.SyncResult
    ) {
        if (running) return
        running = true
        showProgress(message)
        runOnDefaultDispatcher {
            val result = runCatching { task() }
            onMainDispatcher {
                running = false
                result.onSuccess {
                    startActivity(Intent(this@ActivationActivity, SimpleMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    finish()
                }.onFailure { error ->
                    SagerNet.stopService()
                    val revoked = (error as? ManagedApiException)?.revoked == true
                    if (revoked || !ManagedSubscriptionCoordinator.isActivated) {
                        showActivationForm(error.readableMessage)
                    } else {
                        showRetry(error.readableMessage)
                    }
                    Toast.makeText(this@ActivationActivity, error.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showProgress(message: String) {
        binding.activationForm.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        binding.changeCodeButton.visibility = View.GONE
        binding.activationProgress.visibility = View.VISIBLE
        binding.activationStatus.visibility = View.VISIBLE
        binding.activationStatus.text = message
    }

    private fun showActivationForm(message: String? = null) {
        binding.activationProgress.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        binding.changeCodeButton.visibility = View.GONE
        binding.activationForm.visibility = View.VISIBLE
        binding.activationStatus.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.activationStatus.text = message.orEmpty()
        binding.inviteCode.text = null
        binding.inviteCode.error = null
        binding.activateButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.simple_accent_green)
        )
    }

    private fun showRetry(message: String) {
        binding.activationForm.visibility = View.GONE
        binding.activationProgress.visibility = View.GONE
        binding.activationStatus.visibility = View.VISIBLE
        binding.activationStatus.text = "验证失败：$message\n请检查网络后重试"
        binding.retryButton.visibility = View.VISIBLE
        binding.changeCodeButton.visibility = View.VISIBLE
    }

    private fun switchSubscriptionCode() {
        if (running) return
        showActivationForm()
    }
}
