package com.excellanto.mycrmsync.data

import android.util.Log
import com.excellanto.mycrmsync.data.api.GhlApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prints the full GHL location user list to Logcat (filter tag [TAG]).
 * Used on app launch and when Home first loads so first-login flows also see output.
 */
object GhlStaffLogger {
    const val TAG = "GHL_STAFF"

    private var lastLogEpochMs: Long = 0L
    private const val DEBOUNCE_MS = 2500L

    suspend fun logAllStaffIfConfigured(prefs: PrefsManager) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(GhlStaffLogger) {
            if (now - lastLogEpochMs < DEBOUNCE_MS) return@withContext
            lastLogEpochMs = now
        }

        if (!prefs.hasCredentials()) {
            Log.d(TAG, "Staff list skipped (no GHL API key / location yet)")
            return@withContext
        }
        val token = prefs.getApiKey() ?: run {
            Log.d(TAG, "Staff list skipped (no API key)")
            return@withContext
        }
        val locationId = prefs.getLocationId() ?: run {
            Log.d(TAG, "Staff list skipped (no location id)")
            return@withContext
        }
        try {
            val response = GhlApiService.create().getUsers("Bearer $token", locationId)
            val users = response.users
            val selfId = prefs.getUserId()
            Log.i(TAG, "========== GHL STAFF USERS (count=${users.size}, locationId=$locationId) ==========")
            users.forEachIndexed { i, u ->
                val role = u.effectiveType() ?: "—"
                val adminLike = GhlRole.isAdmin(u.effectiveType())
                val nested = u.roles?.let { "roles.type=${it.type} roles.role=${it.role}" } ?: "roles=null"
                Log.i(
                    TAG,
                    "  [$i] id=${u.id} | name=${u.name} | email=${u.email} | " +
                        "topType=${u.type} topRole=${u.role} | $nested | " +
                        "effective=$role | adminLike=$adminLike | currentUser=${u.id == selfId}",
                )
            }
            Log.i(
                TAG,
                "  session: userId=$selfId | savedRole=${prefs.getUserRole()} | isAdmin=${prefs.isAdmin()}",
            )
            Log.i(TAG, "========== END GHL STAFF LIST ==========")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch GHL users: ${e.message}", e)
        }
    }
}
