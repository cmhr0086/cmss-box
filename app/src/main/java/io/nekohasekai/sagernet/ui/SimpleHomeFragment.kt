package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.databinding.LayoutSimpleHomeBinding

class SimpleHomeFragment : Fragment(R.layout.layout_simple_home) {
    private var _binding: LayoutSimpleHomeBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutSimpleHomeBinding.bind(view)

        binding.appTitle.setOnLongClickListener {
            startActivity(Intent(requireContext(), MainActivity::class.java))
            true
        }
        binding.connectButton.setOnClickListener {
            (activity as? SimpleMainActivity)?.startConnectionActionWithDelay()
        }
        binding.latencyCard.setOnClickListener {
            (activity as? SimpleMainActivity)?.startLatencyTest()
        }
        binding.latencyInfo.setOnClickListener {
            (activity as? SimpleMainActivity)?.startLatencyTest()
        }
    }

    fun renderConnectionState(state: BaseService.State, lastFailureMessage: String?) {
        val failed = state == BaseService.State.Stopped && lastFailureMessage != null
        val buttonColor = when {
            state.connected -> R.color.simple_accent_green
            else -> R.color.simple_button_idle
        }
        binding.connectButton.isEnabled = state != BaseService.State.Connecting &&
                state != BaseService.State.Stopping
        binding.connectButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), buttonColor)
        )
        when {
            failed -> {
                binding.connectionStatus.text = "连接失败"
                binding.statusDescription.text = lastFailureMessage ?: "请重试"
                binding.connectButton.text = "重新连接"
            }

            state == BaseService.State.Connecting -> {
                binding.connectionStatus.text = "连接中"
                binding.statusDescription.text = "正在建立连接"
                binding.connectButton.text = "连接中..."
            }

            state == BaseService.State.Connected -> {
                binding.connectionStatus.text = "已连接"
                binding.statusDescription.text = "网络已受保护"
                binding.connectButton.text = "断开"
            }

            state == BaseService.State.Stopping -> {
                binding.connectionStatus.text = "停止中"
                binding.statusDescription.text = "正在关闭连接"
                binding.connectButton.text = "停止中..."
            }

            else -> {
                binding.connectionStatus.text = "未连接"
                binding.statusDescription.text = "轻触连接网络"
                binding.connectButton.text = "连接"
            }
        }
    }

    fun setLatencyDisplay(text: String, colorRes: Int) {
        binding.latencyInfo.text = text
        binding.latencyInfo.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
