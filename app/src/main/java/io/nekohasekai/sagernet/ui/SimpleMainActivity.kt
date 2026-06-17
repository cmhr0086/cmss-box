package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutSimpleMainBinding
import io.nekohasekai.sagernet.group.BuiltinSubscriptionInitializer
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class SimpleMainActivity : ThemedActivity() {

    private lateinit var binding: LayoutSimpleMainBinding
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutSimpleMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()

        binding.appTitle.setOnLongClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            true
        }

        binding.connectButton.setOnClickListener {
            isConnected = !isConnected
            updateConnectionState()
        }

        updateConnectionState()
        runOnDefaultDispatcher {
            BuiltinSubscriptionInitializer.initializeIfNeeded()
        }
    }

    private fun updateConnectionState() {
        if (isConnected) {
            binding.connectionStatus.text = "已连接"
            binding.statusDescription.text = "网络已受保护"
            binding.connectButton.text = "断开"
            binding.connectButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.simple_accent_green)
            )
        } else {
            binding.connectionStatus.text = "未连接"
            binding.statusDescription.text = "轻触连接网络"
            binding.connectButton.text = "连接"
            binding.connectButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.simple_button_idle)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        val background = ContextCompat.getColor(this, R.color.simple_bg_start)
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
