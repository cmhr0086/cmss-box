package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.readableMessage
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

object RemoteConfigSubscriptionManager {

    data class Result(
        val configured: Boolean,
        val updated: Boolean,
        val skipped: Boolean = false,
        val errorMessage: String? = null,
        val groupId: Long = 0L
    ) {
        val failed: Boolean get() = errorMessage != null
    }

    private data class RemoteConfig(
        val version: Int,
        val subUrl: String,
        val subName: String?,
        val forceReplace: Boolean,
        val updateIntervalMinutes: Int
    )

    suspend fun checkAndUpdate(force: Boolean): Result {
        val synced = syncRemoteConfigOnly(force)
        if (!synced.configured || synced.failed || synced.skipped) return synced

        val group = SagerDatabase.groupDao.getById(synced.groupId)
            ?: return Result(
                configured = true,
                updated = false,
                errorMessage = "订阅分组不存在",
                groupId = synced.groupId
            )

        val updated = GroupUpdater.executeUpdate(group, false)
        if (!updated) {
            return Result(
                configured = true,
                updated = false,
                errorMessage = "订阅更新失败",
                groupId = group.id
            )
        }

        BuiltinSubscriptionInitializer.selectFirstProfileIfNeeded(group.id)

        return Result(configured = true, updated = true, groupId = group.id)
    }

    suspend fun syncRemoteConfigOnly(force: Boolean): Result {
        val configUrl = BuildConfig.REMOTE_CONFIG_URL.trim()
        if (configUrl.isBlank()) return Result(configured = false, updated = false, skipped = true)

        val now = System.currentTimeMillis() / 1000L
        if (!force) {
            val intervalSeconds = DataStore.remoteConfigUpdateIntervalMinutes
                .takeIf { it > 0 }
                ?.toLong()
                ?.times(60L)
                ?: DEFAULT_INTERVAL_SECONDS
            if (now - DataStore.remoteConfigLastFetchTime < intervalSeconds) {
                return Result(configured = true, updated = false, skipped = true)
            }
        }

        val config = try {
            fetchConfig(configUrl)
        } catch (e: Throwable) {
            Logs.w("remote config fetch failed: ${e.readableMessage}", e)
            return Result(configured = true, updated = false, errorMessage = e.readableMessage)
        }

        val group = try {
            findOrCreateBuiltinSubscriptionGroup(config)
        } catch (e: Throwable) {
            Logs.w("remote config group prepare failed: ${e.readableMessage}", e)
            return Result(configured = true, updated = false, errorMessage = e.readableMessage)
        }

        val oldLink = group.subscription?.link.orEmpty()
        if (oldLink != config.subUrl) {
            group.subscription?.link = config.subUrl
            if (!config.subName.isNullOrBlank()) group.name = config.subName
            GroupManager.updateGroup(group)
        } else if (!config.subName.isNullOrBlank() && group.name != config.subName) {
            group.name = config.subName
            GroupManager.updateGroup(group)
        }

        val updated = GroupUpdater.executeUpdate(group, false)
        if (!updated) {
            return Result(configured = true, updated = false, errorMessage = "订阅更新失败")
        }

        DataStore.remoteConfigCurrentSubUrl = config.subUrl
        DataStore.remoteConfigLastFetchTime = now
        DataStore.remoteConfigVersion = config.version
        DataStore.remoteConfigUpdateIntervalMinutes = config.updateIntervalMinutes
        DataStore.selectedGroup = group.id

        return Result(configured = true, updated = true, groupId = group.id)
    }

    private fun fetchConfig(configUrl: String): RemoteConfig {
        val response = Libcore.newHttpClient().newRequest().apply {
            setURL(configUrl)
            setUserAgent(USER_AGENT)
        }.execute()
        val json = JSONObject(Util.getStringBox(response.contentString))
        val subUrl = json.optString("sub_url").trim()
        if (subUrl.isBlank()) error("remote sub_url is empty")
        return RemoteConfig(
            version = json.optInt("version", 0),
            subUrl = subUrl,
            subName = json.optString("sub_name").trim().takeIf { it.isNotBlank() },
            forceReplace = json.optBoolean("force_replace", true),
            updateIntervalMinutes = json.optInt("update_interval_minutes", DEFAULT_INTERVAL_MINUTES)
                .coerceAtLeast(1)
        )
    }

    private suspend fun findOrCreateBuiltinSubscriptionGroup(config: RemoteConfig): ProxyGroup {
        val groups = SagerDatabase.groupDao.allGroups()
            .filter { it.type == GroupType.SUBSCRIPTION && it.subscription != null }

        val currentRemoteUrl = DataStore.remoteConfigCurrentSubUrl
        groups.find { currentRemoteUrl.isNotBlank() && it.subscription?.link == currentRemoteUrl }
            ?.let { return it }

        groups.find { it.subscription?.link == config.subUrl }?.let { return it }

        val builtInUrl = BuildConfig.BUILTIN_SUB_URL.trim()
        groups.find { builtInUrl.isNotBlank() && it.subscription?.link == builtInUrl }
            ?.let { return it }

        val selectedGroup = SagerDatabase.groupDao.getById(DataStore.selectedGroup)
        if (config.forceReplace &&
            selectedGroup?.type == GroupType.SUBSCRIPTION &&
            selectedGroup.subscription != null
        ) {
            return selectedGroup
        }

        return BuiltinSubscriptionInitializer.createBuiltinGroup(config.subUrl, config.subName)
    }

    private const val DEFAULT_INTERVAL_MINUTES = 10
    private const val DEFAULT_INTERVAL_SECONDS = DEFAULT_INTERVAL_MINUTES * 60L
}
