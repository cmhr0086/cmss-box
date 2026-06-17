package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.RemoteException
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutSimpleMainBinding
import io.nekohasekai.sagernet.group.BuiltinSubscriptionInitializer
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class SimpleMainActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: LayoutSimpleMainBinding
    private var lastFailureMessage: String? = null
    private val connection = SagerConnection(
        SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND,
        true
    )
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) { denied ->
        if (denied) {
            lastFailureMessage = null
            updateConnectionState(BaseService.State.Stopped)
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

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
            when {
                DataStore.serviceState.canStop -> SagerNet.stopService()
                else -> startSelectedProfile()
            }
        }

        updateConnectionState(DataStore.serviceState)
        connection.connect(this, this)
        runOnDefaultDispatcher {
            BuiltinSubscriptionInitializer.initializeIfNeeded()
        }
    }

    private fun startSelectedProfile() {
        updateConnectionState(BaseService.State.Connecting)
        runOnDefaultDispatcher {
            val ready = ensureSelectedProfile()
            onMainDispatcher {
                if (ready) {
                    connect.launch(null)
                } else {
                    lastFailureMessage = "请先导入或选择节点"
                    updateConnectionState(BaseService.State.Stopped)
                    Toast.makeText(this@SimpleMainActivity, lastFailureMessage, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun ensureSelectedProfile(): Boolean {
        val selected = DataStore.selectedProxy
        if (selected > 0L && SagerDatabase.proxyDao.getById(selected) != null) return true

        val firstProfile = SagerDatabase.proxyDao.getAll().minWithOrNull(
            compareBy({ it.groupId }, { it.userOrder }, { it.id })
        ) ?: return false

        DataStore.selectedProxy = firstProfile.id
        DataStore.currentProfile = firstProfile.id
        DataStore.selectedGroup = firstProfile.groupId
        return true
    }

    private fun updateConnectionState(state: BaseService.State) {
        val failed = state == BaseService.State.Stopped && lastFailureMessage != null
        val buttonColor = when {
            state.connected -> R.color.simple_accent_green
            else -> R.color.simple_button_idle
        }

        binding.connectButton.isEnabled = state != BaseService.State.Connecting &&
                state != BaseService.State.Stopping
        binding.connectButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, buttonColor)
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

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        lastFailureMessage = if (msg != null && state == BaseService.State.Stopped) msg else null
        updateConnectionState(state)
    }

    override fun onServiceConnected(service: ISagerNetService) {
        val state = try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
        lastFailureMessage = null
        updateConnectionState(state)
    }

    override fun onServiceDisconnected() {
        updateConnectionState(BaseService.State.Idle)
    }

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        lastFailureMessage = "缺少插件：$pluginName"
        updateConnectionState(BaseService.State.Stopped)
        Toast.makeText(this, lastFailureMessage, Toast.LENGTH_LONG).show()
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) = Unit

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
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
