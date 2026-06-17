package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.databinding.LayoutSimpleMainBinding

class SimpleMainActivity : ThemedActivity() {

    private lateinit var binding: LayoutSimpleMainBinding
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutSimpleMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appTitle.setOnLongClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            true
        }

        binding.connectButton.setOnClickListener {
            isConnected = !isConnected
            updateConnectionState()
        }

        updateConnectionState()
    }

    private fun updateConnectionState() {
        if (isConnected) {
            binding.connectionStatus.text = "已连接"
            binding.connectButton.text = "断开"
        } else {
            binding.connectionStatus.text = "未连接"
            binding.connectButton.text = "连接"
        }
    }
}
