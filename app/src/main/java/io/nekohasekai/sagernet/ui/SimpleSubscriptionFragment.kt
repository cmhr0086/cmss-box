package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutSimpleSubscriptionBinding
import moe.matsuri.nb4a.utils.toBytesString

class SimpleSubscriptionFragment : Fragment(R.layout.layout_simple_subscription) {
    private var _binding: LayoutSimpleSubscriptionBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutSimpleSubscriptionBinding.bind(view)
        binding.updateSubscriptionButton.setOnClickListener {
            (activity as? SimpleMainActivity)?.showSubscriptionUpdateDialog()
        }
    }

    fun setUpdateButtonEnabled(enabled: Boolean) {
        _binding?.updateSubscriptionButton?.isEnabled = enabled
        _binding?.updateSubscriptionButton?.alpha = if (enabled) 1f else 0.6f
    }

    fun renderTrafficInfo(userInfo: String?) {
        if (userInfo.isNullOrBlank()) {
            binding.trafficInfo.text = "剩余 未知"
            binding.expireInfo.text = "到期 未知"
            binding.trafficDetail.text = "已用 未知 / 共 未知"
            binding.trafficProgress.visibility = View.GONE
            return
        }

        val total = subscriptionValue(userInfo, "total")
        val upload = subscriptionValue(userInfo, "upload") ?: 0L
        val download = subscriptionValue(userInfo, "download") ?: 0L
        val used = upload + download

        if (total == null || total <= 0L) {
            binding.trafficInfo.text = "剩余 未知"
            binding.expireInfo.text = "到期 ${expireDateText(userInfo)}"
            binding.trafficDetail.text = "已用 ${used.toBytesString()} / 共 未知"
            binding.trafficProgress.visibility = View.GONE
            return
        }

        val remaining = (total - upload - download).coerceAtLeast(0L)
        val progress = ((used.coerceAtMost(total) * 1000L) / total).toInt()
        binding.trafficInfo.text = "剩余 ${remaining.toBytesString()}"
        binding.expireInfo.text = "到期 ${expireDateText(userInfo)}"
        binding.trafficDetail.text = "已用 ${used.toBytesString()} / 共 ${total.toBytesString()}"
        binding.trafficProgress.visibility = View.VISIBLE
        binding.trafficProgress.progress = progress
        binding.trafficProgress.progressTintList = ContextCompat.getColorStateList(
            requireContext(),
            if (progress >= 900) R.color.material_red_500 else R.color.simple_accent_green
        )
    }

    private fun subscriptionValue(userInfo: String, key: String): Long? {
        return "$key=([0-9]+)".toRegex()
            .find(userInfo)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private fun expireDateText(userInfo: String): String {
        val expire = subscriptionValue(userInfo, "expire") ?: return "未知"
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(expire * 1000L))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
