package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.api.GhlApiService
import com.excellanto.mycrmsync.data.model.GhlUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fetches the full GHL user list for the current location and updates the logged-in user's
 * role/name in prefs.
 */
object GhlStaffDirectorySync {

    private val HOME_THROTTLE_MS = TimeUnit.MINUTES.toMillis(20)

    /**
     * Always runs: use when opening the dashboard or pull-to-refresh.
     * Refreshes role from GHL and repopulates Postgres staff rows.
     */
    suspend fun refresh(prefs: PrefsManager) = withContext(Dispatchers.IO) {
        doFetch(prefs, updateTimestamp = true)
    }

    /**
     * Lighter call from Home: skips work if we synced recently, unless [force] is true
     * (e.g. missing [PrefsManager.getUserRole]).
     */
    suspend fun refreshThrottled(prefs: PrefsManager, force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getGhlStaffSyncedAt() < HOME_THROTTLE_MS) {
            return@withContext
        }
        doFetch(prefs, updateTimestamp = true)
    }

    private suspend fun doFetch(prefs: PrefsManager, updateTimestamp: Boolean) {
        val token = prefs.getApiKey() ?: return
        val locationId = prefs.getLocationId() ?: return
        val selfId = prefs.getUserId()

        val api = GhlApiService.create()
        val response = api.getUsers("Bearer $token", locationId)
        val all: List<GhlUser> = response.users
        if (all.isEmpty()) return

        if (selfId != null) {
            all.firstOrNull { it.id == selfId }?.let { me ->
                prefs.saveUserRole(me.effectiveType())
                prefs.saveUserName(me.name)
            }
        }

        if (updateTimestamp) {
            prefs.setGhlStaffSyncedAt(System.currentTimeMillis())
        }
    }
}
