package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.readableMessage

object BuiltinSubscriptionInitializer {

    suspend fun initializeIfNeeded() {
        val url = BuildConfig.BUILTIN_SUB_URL.trim()
        if (url.isBlank()) return
        if (DataStore.builtinSubInitialized) return

        if (hasUserConfiguration()) {
            DataStore.builtinSubInitialized = true
            Logs.d("builtin subscription skipped: user configuration exists")
            return
        }

        var group: ProxyGroup? = null
        try {
            group = createBuiltinGroup(url, "CMSS-Box")
               val updated = GroupUpdater.executeUpdate(group, false)
               if (updated) {
                   DataStore.selectedGroup = group.id
                   selectFirstProfileIfNeeded(group.id)
                   DataStore.builtinSubInitialized = true
                   Logs.d("builtin subscription initialized")
               } else {
                cleanupFailedGroup(group)
                Logs.w("builtin subscription update failed")
            }
        } catch (e: Throwable) {
            group?.let { cleanupFailedGroup(it) }
            Logs.w("builtin subscription init failed: ${e.readableMessage}", e)
        }
    }

    private fun hasUserConfiguration(): Boolean {
        if (SagerDatabase.proxyDao.getAll().isNotEmpty()) return true
       return SagerDatabase.groupDao.allGroups().any {
           !it.ungrouped || it.type == GroupType.SUBSCRIPTION
       }
   }

   suspend fun createBuiltinGroup(url: String, name: String?): ProxyGroup {
       return GroupManager.createGroup(
           ProxyGroup(
               type = GroupType.SUBSCRIPTION,
               name = name.takeIf { !it.isNullOrBlank() } ?: "CMSS-Box",
               subscription = SubscriptionBean().applyDefaultValues().apply {
                   link = url
               }
           )
       )
   }

   fun selectFirstProfileIfNeeded(groupId: Long) {
       if (DataStore.selectedProxy > 0L &&
           SagerDatabase.proxyDao.getById(DataStore.selectedProxy) != null
       ) {
           return
       }
       val firstProfile = SagerDatabase.proxyDao.getByGroup(groupId).minWithOrNull(
           compareBy({ it.userOrder }, { it.id })
       ) ?: return
       DataStore.selectedProxy = firstProfile.id
       DataStore.currentProfile = firstProfile.id
   }

   private suspend fun cleanupFailedGroup(group: ProxyGroup) {
       if (group.id > 0L) {
            GroupManager.deleteGroup(group.id)
        }
    }
}
