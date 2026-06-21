package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.RemoteException
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
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
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutSimpleMainBinding
import io.nekohasekai.sagernet.group.BuiltinSubscriptionInitializer
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RemoteConfigSubscriptionManager
import io.nekohasekai.sagernet.managed.ManagedSubscriptionCoordinator
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.dialog.SubscriptionUpdateProgressDialogFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import moe.matsuri.nb4a.utils.toBytesString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SimpleMainActivity : ThemedActivity(), SagerConnection.Callback, GroupManager.Listener {
    companion object {
        const val EXTRA_OPEN_UPDATE_DIALOG = "open_update_dialog"
    }

    private lateinit var binding: LayoutSimpleMainBinding
    private var lastFailureMessage: String? = null
    private var connectionActionJob: Job? = null
    private var latencyTestJob: Job? = null
    private var subscriptionUpdateJob: Job? = null
    private var autoLatencyTestDone = false
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
    private val settingsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            if (data.getBooleanExtra(SimpleSettingsActivity.RESULT_OPEN_UPDATE_DIALOG, false)) {
                showSubscriptionUpdateDialog()
            }
            if (data.getBooleanExtra(SimpleSettingsActivity.RESULT_REFRESH_HOME, false)) {
                updateLatencyFromSelectedProfile()
                updateSubscriptionInfo()
                updateConnectionState(DataStore.serviceState)
            }
        }
    }

    private enum class UpdateStepState {
        Pending,
        Running,
        Done,
        Failed
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
        binding.settingsButton.setOnClickListener {
            settingsLauncher.launch(Intent(this, SimpleSettingsActivity::class.java))
        }

        binding.connectButton.setOnClickListener {
            startConnectionActionWithDelay()
        }
        binding.latencyCard.setOnClickListener {
            startLatencyTest()
        }
        binding.latencyInfo.setOnClickListener {
            startLatencyTest()
        }
        binding.updateSubscriptionButton.setOnClickListener {
            showSubscriptionUpdateDialog()
        }

        updateLatencyFromSelectedProfile()
        updateSubscriptionInfo()
        updateConnectionState(DataStore.serviceState)
        connection.connect(this, this)
        GroupManager.addListener(this)
        runOnDefaultDispatcher {
            if (!ManagedSubscriptionCoordinator.isActivated) {
                val remoteResult = RemoteConfigSubscriptionManager.checkAndUpdate(false)
                if (!remoteResult.updated) {
                    BuiltinSubscriptionInitializer.initializeIfNeeded()
                    if (!remoteResult.configured) {
                        updateCurrentSubscriptionGroup(true)
                    }
                }
            }
            onMainDispatcher {
                updateLatencyFromSelectedProfile()
                updateSubscriptionInfo()
                handleIntentAction(intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    private fun startConnectionActionWithDelay() {
        if (connectionActionJob?.isActive == true) return
        val shouldStop = DataStore.serviceState.canStop
        updateConnectionState(
            if (shouldStop) BaseService.State.Stopping else BaseService.State.Connecting
        )
        connectionActionJob = runOnDefaultDispatcher {
            delay(1000)
            onMainDispatcher {
                if (shouldStop) {
                    if (DataStore.serviceState.canStop) SagerNet.stopService()
                } else {
                    if (!DataStore.serviceState.canStop) startSelectedProfile()
                }
            }
        }
    }

    private fun startSelectedProfile() {
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
                if (!autoLatencyTestDone) {
                    autoLatencyTestDone = true
                    startLatencyTest()
                }
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
                autoLatencyTestDone = false
            }
        }
    }

    private fun selectedProfile(): ProxyEntity? {
        val selected = DataStore.selectedProxy
        if (selected <= 0L) return null
        return SagerDatabase.proxyDao.getById(selected)
    }

    private fun updateLatencyFromSelectedProfile() {
        val profile = selectedProfile()
        when {
            profile == null -> setLatencyDisplay("--", R.color.simple_text_secondary)
            profile.status == 1 && profile.ping > 0 -> {
                setLatencyDisplay("${profile.ping} ms", latencyColor(profile.ping))
            }
            profile.status == 2 || profile.status == 3 -> {
                setLatencyDisplay("超时", R.color.material_red_500)
            }
            else -> setLatencyDisplay("--", R.color.simple_text_secondary)
        }
    }

    private fun setLatencyDisplay(text: String, colorRes: Int) {
        binding.latencyInfo.text = text
        binding.latencyInfo.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun latencyColor(ping: Int): Int {
        return when {
            ping < 200 -> R.color.material_green_500
            ping < 300 -> R.color.material_yellow_700
            else -> R.color.material_red_500
        }
    }

    private fun startLatencyTest() {
        if (latencyTestJob?.isActive == true) return
        val profile = selectedProfile() ?: run {
            setLatencyDisplay("--", R.color.simple_text_secondary)
            return
        }

        setLatencyDisplay("测试中", R.color.simple_text_secondary)
        latencyTestJob = runOnDefaultDispatcher {
            val result = try {
                val elapsed = if (DataStore.serviceState.connected && connection.service != null) {
                    connection.service!!.urlTest()
                } else {
                    UrlTest().doTest(profile)
                }
                profile.status = 1
                profile.ping = elapsed
                profile.error = null
                ProfileManager.updateProfile(profile)
                elapsed
            } catch (e: Exception) {
                profile.status = 3
                profile.error = e.readableMessage
                try {
                    ProfileManager.updateProfile(profile)
                } catch (_: Exception) {
                }
                null
            }

            onMainDispatcher {
                if (result != null) {
                    setLatencyDisplay("$result ms", latencyColor(result))
                } else {
                    setLatencyDisplay("超时", R.color.material_red_500)
                }
            }
        }
    }

    private fun updateSubscriptionInfo() {
        val group = currentSubscriptionGroup()
        val userInfo = group?.subscription?.subscriptionUserinfo

        if (userInfo.isNullOrBlank()) {
            binding.trafficInfo.text = "剩余 未知"
            binding.expireInfo.text = "到期 未知"
            binding.trafficDetail.text = "已用 未知 / 共 未知"
            binding.trafficProgress.visibility = View.GONE
            return
        }

        updateTrafficDisplay(userInfo)
    }

    private fun currentSubscriptionGroup(): ProxyGroup? {
        val groupId = selectedProfile()?.groupId ?: DataStore.selectedGroup.takeIf { it > 0L }
        return groupId?.let { SagerDatabase.groupDao.getById(it) }?.takeIf { it.subscription != null }
    }

    private fun subscriptionValue(userInfo: String, key: String): Long? {
        return "$key=([0-9]+)".toRegex()
            .find(userInfo)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private fun updateTrafficDisplay(userInfo: String) {
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
        binding.trafficProgress.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (progress >= 900) R.color.material_red_500 else R.color.simple_accent_green
            )
        )
    }

    private fun expireDateText(userInfo: String): String {
        val expire = subscriptionValue(userInfo, "expire") ?: return "未知"
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expire * 1000L))
    }

    private fun updateCurrentSubscription(throttled: Boolean) {
        if (subscriptionUpdateJob?.isActive == true) return

        binding.updateSubscriptionButton.isEnabled = false
        binding.updateSubscriptionButton.text = "更新中"
        subscriptionUpdateJob = runOnDefaultDispatcher {
            val remoteResult = RemoteConfigSubscriptionManager.checkAndUpdate(!throttled)
            val success = when {
                remoteResult.updated -> true
                remoteResult.configured && !remoteResult.failed -> true
                else -> updateCurrentSubscriptionGroup(throttled)
            }
            val remoteFailure = remoteResult.configured && remoteResult.failed

            onMainDispatcher {
                binding.updateSubscriptionButton.isEnabled = true
                binding.updateSubscriptionButton.text = "更新"
                updateSubscriptionInfo()
                when {
                    remoteFailure && !throttled -> Toast.makeText(
                        this@SimpleMainActivity,
                        "远程配置获取失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    !success && !throttled -> Toast.makeText(
                        this@SimpleMainActivity,
                        "订阅更新失败",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }
            }
        }
    }

    private fun showSubscriptionUpdateDialog() {
        if (subscriptionUpdateJob?.isActive == true) return

        val dialog = SubscriptionUpdateProgressDialogFragment()
        dialog.show(supportFragmentManager, SubscriptionUpdateProgressDialogFragment.TAG)
        dialog.setSummary("准备更新...")
        dialog.updateRemoteStep(SubscriptionUpdateProgressDialogFragment.StepState.Pending)
        dialog.updateLocalStep(SubscriptionUpdateProgressDialogFragment.StepState.Pending)
        dialog.setClosable(false)

        binding.updateSubscriptionButton.isEnabled = false
        subscriptionUpdateJob = runOnDefaultDispatcher {
            onMainDispatcher {
                findUpdateDialog()?.apply {
                    setSummary("正在更新远端订阅配置")
                    updateRemoteStep(SubscriptionUpdateProgressDialogFragment.StepState.Running)
                }
            }

            val managed = ManagedSubscriptionCoordinator.isActivated
            val managedSync = if (managed) {
                runCatching { ManagedSubscriptionCoordinator.syncConfigOnly() }
            } else null
            val remoteResult = if (!managed) {
                RemoteConfigSubscriptionManager.syncRemoteConfigOnly(true)
            } else null
            val remoteFailure = managedSync?.exceptionOrNull()?.readableMessage
                ?: remoteResult?.errorMessage
            val remoteConfigured = managedSync?.isSuccess == true ||
                    (remoteResult?.configured == true && !remoteResult.failed)
            if (!remoteConfigured) {
                val message = remoteFailure ?: "远程配置未配置"
                onMainDispatcher {
                    findUpdateDialog()?.apply {
                        setSummary("更新失败")
                        updateRemoteStep(
                            SubscriptionUpdateProgressDialogFragment.StepState.Failed,
                            message
                        )
                        setClosable(true)
                    }
                    binding.updateSubscriptionButton.isEnabled = true
                    Toast.makeText(this@SimpleMainActivity, "远程配置获取失败", Toast.LENGTH_SHORT)
                        .show()
                }
                return@runOnDefaultDispatcher
            }

            onMainDispatcher {
                findUpdateDialog()?.apply {
                    updateRemoteStep(SubscriptionUpdateProgressDialogFragment.StepState.Done)
                    setSummary("正在拉取本地订阅内容")
                    updateLocalStep(SubscriptionUpdateProgressDialogFragment.StepState.Running)
                }
            }

            val localUpdated = if (managed) {
                ManagedSubscriptionCoordinator.updateSubscription(managedSync!!.getOrThrow().groupId)
            } else {
                updateCurrentSubscriptionGroup(false)
            }
            onMainDispatcher {
                if (localUpdated) {
                    findUpdateDialog()?.apply {
                        updateLocalStep(SubscriptionUpdateProgressDialogFragment.StepState.Done)
                        setSummary("更新完成")
                        setClosable(true)
                    }
                    updateLatencyFromSelectedProfile()
                    updateSubscriptionInfo()
                } else {
                    findUpdateDialog()?.apply {
                        updateLocalStep(
                            SubscriptionUpdateProgressDialogFragment.StepState.Failed,
                            "订阅更新失败"
                        )
                        setSummary("更新失败")
                        setClosable(true)
                    }
                    Toast.makeText(this@SimpleMainActivity, "订阅更新失败", Toast.LENGTH_SHORT)
                        .show()
                }
                binding.updateSubscriptionButton.isEnabled = true
            }
        }
    }

    private fun findUpdateDialog(): SubscriptionUpdateProgressDialogFragment? {
        return supportFragmentManager.findFragmentByTag(
            SubscriptionUpdateProgressDialogFragment.TAG
        ) as? SubscriptionUpdateProgressDialogFragment
    }

    private fun handleIntentAction(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE_DIALOG, false) == true) {
            intent.removeExtra(EXTRA_OPEN_UPDATE_DIALOG)
            showSubscriptionUpdateDialog()
        }
    }

    private suspend fun updateCurrentSubscriptionGroup(throttled: Boolean): Boolean {
        val group = currentSubscriptionGroup() ?: return false
        val subscription = group.subscription ?: return false
        if (throttled) {
            val now = (System.currentTimeMillis() / 1000L).toInt()
            if (now - subscription.lastUpdated < 10 * 60) return true
        }
        return runCatching {
            GroupUpdater.executeUpdate(group, false)
        }.getOrElse { false }
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
        updateLatencyFromSelectedProfile()
        updateSubscriptionInfo()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    private fun refreshServiceState() {
        val state = try {
            connection.service?.let { BaseService.State.values()[it.state] }
                ?: DataStore.serviceState
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
        if (state != BaseService.State.Stopped) {
            lastFailureMessage = null
        }
        updateLatencyFromSelectedProfile()
        updateSubscriptionInfo()
        updateConnectionState(state)
    }

    override suspend fun groupAdd(group: ProxyGroup) {
        onMainDispatcher {
            updateSubscriptionInfo()
        }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        onMainDispatcher {
            updateSubscriptionInfo()
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        onMainDispatcher {
            updateSubscriptionInfo()
        }
    }

    override suspend fun groupUpdated(groupId: Long) {
        onMainDispatcher {
            updateSubscriptionInfo()
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
        refreshServiceState()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        connectionActionJob?.cancel()
        latencyTestJob?.cancel()
        subscriptionUpdateJob?.cancel()
        GroupManager.removeListener(this)
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
