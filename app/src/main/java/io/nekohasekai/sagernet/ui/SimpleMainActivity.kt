package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.RemoteException
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.commitNow
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
import io.nekohasekai.sagernet.managed.ManagedApiException
import io.nekohasekai.sagernet.managed.ManagedSubscriptionCoordinator
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.dialog.SubscriptionUpdateProgressDialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.toBytesString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SimpleMainActivity : ThemedActivity(), SagerConnection.Callback, GroupManager.Listener {
    companion object {
        const val EXTRA_OPEN_UPDATE_DIALOG = "open_update_dialog"
        const val EXTRA_START_TAB = "start_tab"
        const val START_TAB_HOME = 0
        const val START_TAB_SUBSCRIPTION = 1
        const val START_TAB_SETTINGS = 2

        private const val TAG_HOME = "simple_home_fragment"
        private const val TAG_SUBSCRIPTION = "simple_subscription_fragment"
        private const val TAG_SETTINGS = "simple_settings_fragment"
    }

    private lateinit var binding: LayoutSimpleMainBinding
    private var lastFailureMessage: String? = null
    private var connectionActionJob: Job? = null
    private var latencyTestJob: Job? = null
    private var subscriptionUpdateJob: Job? = null
    private var autoLatencyTestDone = false
    private var currentTab = START_TAB_HOME
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
        bindWindowInsets()
        ensureFragments()
        setupBottomNavigation()

        connection.connect(this, this)
        GroupManager.addListener(this)
        runOnDefaultDispatcher {
            var activationRequiredMessage: String? = null
            if (ManagedSubscriptionCoordinator.isActivated) {
                val refreshResult = runCatching { ManagedSubscriptionCoordinator.refresh() }
                refreshResult.exceptionOrNull()?.let { error ->
                    val revoked = (error as? ManagedApiException)?.revoked == true
                    val message = if (revoked) {
                        "设备授权已失效，请重新输入邀请码激活"
                    } else {
                        error.readableMessage
                    }
                    if (revoked) {
                        activationRequiredMessage = message
                    } else {
                        lastFailureMessage = message
                    }
                }
            } else {
                activationRequiredMessage = "请先输入邀请码激活设备"
            }
            onMainDispatcher {
                activationRequiredMessage?.let { message ->
                    Toast.makeText(this@SimpleMainActivity, message, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@SimpleMainActivity, ActivationActivity::class.java).apply {
                        putExtra(ActivationActivity.EXTRA_FORCE_INPUT, true)
                    })
                    finish()
                    return@onMainDispatcher
                }
                updateFromCurrentState()
                handleIntentAction(intent)
            }
        }

        val startTab = intent?.getIntExtra(EXTRA_START_TAB, START_TAB_HOME) ?: START_TAB_HOME
        showTab(startTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTab(intent.getIntExtra(EXTRA_START_TAB, START_TAB_HOME))
        handleIntentAction(intent)
    }

    private fun bindWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = bottom)
            insets
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> showTab(START_TAB_HOME)
                R.id.tab_subscription -> showTab(START_TAB_SUBSCRIPTION)
                R.id.tab_settings -> showTab(START_TAB_SETTINGS)
                else -> false
            }
        }
        binding.bottomNavigation.setOnItemReselectedListener { }
    }

    private fun ensureFragments() {
        if (supportFragmentManager.findFragmentByTag(TAG_HOME) == null) {
            val home = SimpleHomeFragment()
            val subscription = SimpleSubscriptionFragment()
            val settings = SimpleSettingsFragment()
            supportFragmentManager.commitNow {
                add(R.id.page_container, home, TAG_HOME)
                add(R.id.page_container, subscription, TAG_SUBSCRIPTION)
                add(R.id.page_container, settings, TAG_SETTINGS)
                hide(home)
                hide(subscription)
                hide(settings)
            }
        }
    }

    private fun showTab(tab: Int): Boolean {
        ensureFragments()
        if (currentTab == tab && fragmentForTab(tab)?.isVisible == true) return true
        currentTab = tab
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            listOf(
                requireHomeFragment(),
                requireSubscriptionFragment(),
                requireSettingsFragment()
            ).forEach { fragment ->
                if (fragment == fragmentForTab(tab)) {
                    show(fragment)
                } else {
                    hide(fragment)
                }
            }
        }
        val checkedItemId = when (tab) {
            START_TAB_SUBSCRIPTION -> R.id.tab_subscription
            START_TAB_SETTINGS -> R.id.tab_settings
            else -> R.id.tab_home
        }
        if (binding.bottomNavigation.selectedItemId != checkedItemId) {
            binding.bottomNavigation.selectedItemId = checkedItemId
        }
        return true
    }

    private fun fragmentForTab(tab: Int) = when (tab) {
        START_TAB_SUBSCRIPTION -> supportFragmentManager.findFragmentByTag(TAG_SUBSCRIPTION)
        START_TAB_SETTINGS -> supportFragmentManager.findFragmentByTag(TAG_SETTINGS)
        else -> supportFragmentManager.findFragmentByTag(TAG_HOME)
    }

    private fun requireHomeFragment() = supportFragmentManager.findFragmentByTag(TAG_HOME) as SimpleHomeFragment
    private fun requireSubscriptionFragment() = supportFragmentManager.findFragmentByTag(TAG_SUBSCRIPTION) as SimpleSubscriptionFragment
    private fun requireSettingsFragment() = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as SimpleSettingsFragment

    fun openAdvancedMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    fun startConnectionActionWithDelay() {
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

    fun startLatencyTest() {
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

    private fun selectedProfile(): ProxyEntity? {
        val selected = DataStore.selectedProxy
        if (selected <= 0L) return null
        return SagerDatabase.proxyDao.getById(selected)
    }

    private fun updateFromCurrentState() {
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

    private fun updateConnectionState(state: BaseService.State) {
        requireHomeFragment().renderConnectionState(state, lastFailureMessage)
        if (state == BaseService.State.Connected && !autoLatencyTestDone) {
            autoLatencyTestDone = true
            startLatencyTest()
        }
        if (state != BaseService.State.Connected && state != BaseService.State.Connecting) {
            autoLatencyTestDone = false
        }
    }

    private fun setLatencyDisplay(text: String, colorRes: Int) {
        requireHomeFragment().setLatencyDisplay(text, colorRes)
    }

    private fun latencyColor(ping: Int): Int {
        return when {
            ping < 200 -> R.color.material_green_500
            ping < 300 -> R.color.material_yellow_700
            else -> R.color.material_red_500
        }
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

    private fun currentSubscriptionGroup(): ProxyGroup? {
        val groupId = selectedProfile()?.groupId ?: DataStore.selectedGroup.takeIf { it > 0L }
        return groupId?.let { SagerDatabase.groupDao.getById(it) }?.takeIf { it.subscription != null }
    }

    private fun updateSubscriptionInfo() {
        requireSubscriptionFragment().renderTrafficInfo(currentSubscriptionGroup()?.subscription?.subscriptionUserinfo)
    }

    fun onManagedSubscriptionChanged() {
        refreshServiceState()
    }

    fun showSubscriptionUpdateDialog() {
        if (subscriptionUpdateJob?.isActive == true) return

        val dialog = SubscriptionUpdateProgressDialogFragment()
        dialog.show(supportFragmentManager, SubscriptionUpdateProgressDialogFragment.TAG)
        dialog.setSummary("准备更新...")
        dialog.updateRemoteStep(SubscriptionUpdateProgressDialogFragment.StepState.Pending)
        dialog.updateLocalStep(SubscriptionUpdateProgressDialogFragment.StepState.Pending)
        dialog.setClosable(false)

        setSubscriptionActionEnabled(false)
        subscriptionUpdateJob = runOnDefaultDispatcher {
            if (!ManagedSubscriptionCoordinator.isActivated) {
                val message = "请先输入邀请码激活设备"
                onMainDispatcher {
                    findUpdateDialog()?.apply {
                        setSummary("需要激活")
                        updateRemoteStep(
                            SubscriptionUpdateProgressDialogFragment.StepState.Failed,
                            message
                        )
                        updateLocalStep(
                            SubscriptionUpdateProgressDialogFragment.StepState.Failed,
                            "未激活设备不能更新订阅"
                        )
                        setClosable(true)
                    }
                    setSubscriptionActionEnabled(true)
                    Toast.makeText(this@SimpleMainActivity, message, Toast.LENGTH_SHORT).show()
                }
                return@runOnDefaultDispatcher
            }

            onMainDispatcher {
                findUpdateDialog()?.apply {
                    setSummary("正在验证设备并同步模板")
                    updateRemoteStep(SubscriptionUpdateProgressDialogFragment.StepState.Running)
                }
            }

            val managedSync = runCatching { ManagedSubscriptionCoordinator.syncConfigOnly() }
            if (managedSync.isFailure) {
                val message = managedSync.exceptionOrNull()?.let { error ->
                    if ((error as? ManagedApiException)?.revoked == true) {
                        "设备授权已失效，请重新输入邀请码激活"
                    } else {
                        error.readableMessage
                    }
                } ?: "设备验证失败"
                onMainDispatcher {
                    findUpdateDialog()?.apply {
                        setSummary("验证失败")
                        updateRemoteStep(
                            SubscriptionUpdateProgressDialogFragment.StepState.Failed,
                            message
                        )
                        setClosable(true)
                    }
                    setSubscriptionActionEnabled(true)
                    Toast.makeText(this@SimpleMainActivity, message, Toast.LENGTH_SHORT).show()
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

            val localUpdatedResult = runCatching {
                ManagedSubscriptionCoordinator.updateSubscription(managedSync.getOrThrow().groupId)
            }
            onMainDispatcher {
                if (localUpdatedResult.getOrDefault(false)) {
                    findUpdateDialog()?.apply {
                        updateLocalStep(SubscriptionUpdateProgressDialogFragment.StepState.Done)
                        setSummary("更新完成")
                        setClosable(true)
                    }
                    updateFromCurrentState()
                } else {
                    val failureMessage = localUpdatedResult.exceptionOrNull()?.readableMessage
                        ?: "订阅更新失败"
                    findUpdateDialog()?.apply {
                        updateLocalStep(
                            SubscriptionUpdateProgressDialogFragment.StepState.Failed,
                            failureMessage
                        )
                        setSummary("更新失败")
                        setClosable(true)
                    }
                    Toast.makeText(this@SimpleMainActivity, failureMessage, Toast.LENGTH_SHORT)
                        .show()
                }
                setSubscriptionActionEnabled(true)
            }
        }
    }

    private fun setSubscriptionActionEnabled(enabled: Boolean) {
        requireSubscriptionFragment().setUpdateButtonEnabled(enabled)
        requireSettingsFragment().setUpdateRowEnabled(enabled)
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
        updateFromCurrentState()
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
