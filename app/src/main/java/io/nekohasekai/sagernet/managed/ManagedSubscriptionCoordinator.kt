package io.nekohasekai.sagernet.managed

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.BuiltinSubscriptionInitializer
import io.nekohasekai.sagernet.group.GroupUpdater

object ManagedSubscriptionCoordinator {
    data class SyncResult(val groupId: Long, val config: ManagedApiClient.ManagedConfig)

    val isActivated: Boolean get() = ManagedDeviceIdentity.isActivated

    suspend fun activate(inviteCode: String): SyncResult {
        DataStore.managedDeviceId = ManagedApiClient.activate(inviteCode)
        return refresh()
    }

    suspend fun changeSubscriptionCode(inviteCode: String): SyncResult {
        val oldDeviceId = DataStore.managedDeviceId
        val oldGroupId = DataStore.managedGroupId
        val oldSelectedGroup = DataStore.selectedGroup
        val oldTemplateVersion = DataStore.managedTemplateVersion
        val oldLastVerifiedAt = DataStore.managedLastVerifiedAt
        val oldGroupSnapshot = SagerDatabase.groupDao.getById(oldGroupId)?.snapshot()

        DataStore.managedDeviceId = ManagedApiClient.activate(inviteCode)
        return try {
            val synced = syncConfigOnly()
            if (!updateSubscription(synced.groupId)) error("订阅更新失败")
            DataStore.managedLastVerifiedAt = System.currentTimeMillis() / 1000L
            synced
        } catch (e: Exception) {
            restoreManagedState(
                oldDeviceId,
                oldGroupId,
                oldSelectedGroup,
                oldTemplateVersion,
                oldLastVerifiedAt,
                oldGroupSnapshot
            )
            throw e
        }
    }

    suspend fun refresh(): SyncResult {
        val synced = syncConfigOnly()
        if (!updateSubscription(synced.groupId)) error("订阅更新失败")
        DataStore.managedLastVerifiedAt = System.currentTimeMillis() / 1000L
        return synced
    }

    suspend fun syncConfigOnly(): SyncResult {
        val config = fetchConfigOrThrow()
        val group = applyConfig(config)
        return SyncResult(group.id, config)
    }

    suspend fun updateSubscription(groupId: Long): Boolean {
        val group = SagerDatabase.groupDao.getById(groupId) ?: return false
        val updated = GroupUpdater.executeUpdate(group, false)
        if (updated) BuiltinSubscriptionInitializer.selectFirstProfileIfNeeded(group.id)
        return updated
    }

    suspend fun revokeLocalAccess() {
        val groupId = DataStore.managedGroupId
        if (groupId > 0L) GroupManager.deleteGroup(groupId)
        DataStore.managedGroupId = 0L
        DataStore.managedDeviceId = ""
        DataStore.managedLastVerifiedAt = 0L
    }

    suspend fun clearManagedIdentity() {
        revokeLocalAccess()
        ManagedDeviceIdentity.clear()
        DataStore.managedTemplateVersion = 0
        DataStore.selectedProxy = 0L
        DataStore.currentProfile = 0L
        DataStore.selectedGroup = DataStore.currentGroupId()
    }

    private suspend fun fetchConfigOrThrow(): ManagedApiClient.ManagedConfig {
        return try {
            ManagedApiClient.fetchConfig()
        } catch (e: ManagedApiException) {
            if (e.revoked) revokeLocalAccess()
            throw e
        }
    }

    private suspend fun applyConfig(config: ManagedApiClient.ManagedConfig): ProxyGroup {
        var group = SagerDatabase.groupDao.getById(DataStore.managedGroupId)
        if (group?.subscription == null) {
            group = BuiltinSubscriptionInitializer.createBuiltinGroup(
                config.subscriptionUrl,
                config.templateName
            )
        } else if (group.subscription?.link != config.subscriptionUrl || group.name != config.templateName) {
            group.subscription?.link = config.subscriptionUrl
            group.name = config.templateName
            GroupManager.updateGroup(group)
        }
        DataStore.managedGroupId = group.id
        DataStore.managedTemplateVersion = config.templateVersion
        DataStore.selectedGroup = group.id
        return group
    }

    private suspend fun restoreManagedState(
        oldDeviceId: String,
        oldGroupId: Long,
        oldSelectedGroup: Long,
        oldTemplateVersion: Int,
        oldLastVerifiedAt: Long,
        oldGroupSnapshot: ProxyGroup?
    ) {
        val currentGroupId = DataStore.managedGroupId
        if (oldGroupSnapshot == null) {
            if (currentGroupId > 0L) GroupManager.deleteGroup(currentGroupId)
        } else {
            if (currentGroupId > 0L && currentGroupId != oldGroupSnapshot.id) {
                GroupManager.deleteGroup(currentGroupId)
            }
            GroupManager.updateGroup(oldGroupSnapshot)
        }
        DataStore.managedDeviceId = oldDeviceId
        DataStore.managedGroupId = oldGroupId
        DataStore.selectedGroup = oldSelectedGroup
        DataStore.managedTemplateVersion = oldTemplateVersion
        DataStore.managedLastVerifiedAt = oldLastVerifiedAt
    }

    private fun ProxyGroup.snapshot(): ProxyGroup {
        return copy(subscription = subscription?.snapshot())
    }

    private fun SubscriptionBean.snapshot(): SubscriptionBean {
        return SubscriptionBean().also {
            it.type = type
            it.link = link
            it.token = token
            it.forceResolve = forceResolve
            it.deduplication = deduplication
            it.updateWhenConnectedOnly = updateWhenConnectedOnly
            it.customUserAgent = customUserAgent
            it.autoUpdate = autoUpdate
            it.autoUpdateDelay = autoUpdateDelay
            it.lastUpdated = lastUpdated
            it.bytesUsed = bytesUsed
            it.bytesRemaining = bytesRemaining
            it.username = username
            it.expiryDate = expiryDate
            it.protocols = protocols?.toMutableList()
            it.subscriptionUserinfo = subscriptionUserinfo
        }
    }
}
