package com.excellanto.mycrmsync.data

import android.content.Context
import android.content.SharedPreferences
import com.excellanto.mycrmsync.data.auth.AuthSessionManager
import com.excellanto.mycrmsync.data.model.AuthVerifyData

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("crm_prefs", Context.MODE_PRIVATE)

    fun saveGhlCredentials(apiKey: String, locationId: String) {
        prefs.edit().apply {
            putString("api_key", apiKey)
            putString("location_id", locationId)
            apply()
        }
    }

    fun saveUserId(userId: String) {
        prefs.edit().putString("user_id", userId).apply()
    }

    fun saveUserRole(role: String?) {
        prefs.edit().putString("user_role", role?.lowercase()).apply()
    }

    fun saveUserName(name: String?) {
        prefs.edit().putString("user_name", name).apply()
    }

    fun saveAuthSession(data: AuthVerifyData) {
        val integrated = data.user.integratedUser
        val appRole =
            data.userRole?.trim()?.ifBlank { null }
                ?: data.user.userRole?.trim()?.ifBlank { null }
                ?: integrated?.role?.trim()?.ifBlank { null }
        prefs.edit().apply {
            putString("access_token", data.accessToken)
            putString("token_type", data.tokenType)
            putString("token_expires_at", data.expiresAt)
            putInt("auth_user_id", data.user.id)
            putString("auth_user_name", data.user.name)
            putString("auth_user_email", data.user.email)
            putInt("auth_tenant_id", data.user.tenantId)
            putString("auth_intsysuser", data.user.intsysuser)
            appRole?.let { putString("auth_user_role", it.lowercase()) }
            integrated?.id?.let { putString("integrated_user_id", it) }
            integrated?.name?.let { putString("integrated_user_name", it) }
            integrated?.email?.let { putString("integrated_user_email", it) }
            integrated?.role?.let { putString("integrated_user_role", it) }
            data.integratedSystem?.slug?.let { putString("integrated_system_slug", it) }
            data.integratedSystem?.name?.let { putString("integrated_system_name", it) }
            apply()
        }
        saveUserId(integrated?.id ?: data.user.id.toString())
        saveUserName(integrated?.name ?: data.user.name)
        saveUserRole(appRole)
        AuthSessionManager.resetAfterLogin()
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)

    fun getBearerAuthorization(): String? {
        val token = getAccessToken() ?: return null
        val type = prefs.getString("token_type", "Bearer")?.trim().orEmpty().ifEmpty { "Bearer" }
        return "$type $token"
    }

    fun getTokenExpiresAt(): String? = prefs.getString("token_expires_at", null)

    fun getAuthUserId(): String? {
        val id = prefs.getInt("auth_user_id", -1)
        return id.takeIf { it > 0 }?.toString()
    }

    fun getAuthTenantId(): String? {
        val id = prefs.getInt("auth_tenant_id", -1)
        return id.takeIf { it > 0 }?.toString()
    }

    /** Tenant/workspace scope for CRM rows (falls back to legacy GHL location id). */
    fun getCrmScopeId(): String? = getAuthTenantId() ?: getLocationId()

    fun requireCrmSession(): Pair<String, String> {
        val authorization =
            getBearerAuthorization()
                ?: throw IllegalStateException("Not logged in. Please sign in again.")
        val userId =
            getAuthUserId()
                ?: throw IllegalStateException("CRM user id not found. Please sign in again.")
        return authorization to userId
    }

    fun getAuthUserEmail(): String? = prefs.getString("auth_user_email", null)

    fun getIntegratedSystemSlug(): String? = prefs.getString("integrated_system_slug", null)

    fun hasAuthSession(): Boolean = !getAccessToken().isNullOrBlank()

    // GHL credentials come from user input (SharedPreferences)
    fun getApiKey(): String? = prefs.getString("api_key", null)
    fun getLocationId(): String? = prefs.getString("location_id", null)
    fun getUserId(): String? = prefs.getString("user_id", null)
    fun getUserRole(): String? = prefs.getString("user_role", null)

    /** Role from OTP verify (`userrole`, e.g. `tenant_admin` / `tenant_user`). */
    fun getAuthUserRole(): String? =
        prefs.getString("auth_user_role", null)?.trim()?.ifBlank { null } ?: getUserRole()

    fun getUserName(): String? = prefs.getString("user_name", null)

    /** All ids that may refer to the logged-in user across auth + integrated CRM ids. */
    fun knownStaffIdsForCurrentUser(): Set<String> =
        setOfNotNull(
            getRegisterUserId()?.toString(),
            getAuthUserId(),
            getUserId(),
        ).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * True when OTP `userrole` is `tenant_admin`, or legacy GHL admin role strings apply.
     */
    fun isAdmin(): Boolean {
        when (getAuthUserRole()?.trim()?.lowercase()) {
            "tenant_admin" -> return true
            "tenant_user" -> return false
        }
        return GhlRole.isAdmin(getUserRole())
    }

    /** Last successful GHL staff list sync (epoch ms) — throttles background refresh. */
    fun getGhlStaffSyncedAt(): Long = prefs.getLong("ghl_staff_synced_at", 0L)
    fun setGhlStaffSyncedAt(epochMs: Long) {
        prefs.edit().putLong("ghl_staff_synced_at", epochMs).apply()
    }

    fun hasCredentials(): Boolean {
        return getApiKey() != null && getLocationId() != null
    }

    fun hasUser(): Boolean = getUserId() != null

    fun isLoggedIn(): Boolean = hasAuthSession() && hasUser()

  /** Numeric id for `POST api/crm/call-logs/register` (auth user id preferred). */
    fun getRegisterUserId(): Int? {
        getAuthUserId()?.toIntOrNull()?.let { return it }
        return getUserId()?.toIntOrNull()
    }

    fun requireRegisterUserId(): Int =
        getRegisterUserId()
            ?: throw IllegalStateException("User id not set. Please sign in and select a user.")

    /** Max [android.provider.CallLog.Calls.DATE] (epoch ms) we have processed for CRM upload. */
    fun getDeviceCallLogSyncWatermarkMs(): Long =
        prefs.getLong("device_call_log_sync_watermark_ms", 0L)

    fun setDeviceCallLogSyncWatermarkMs(epochMs: Long) {
        prefs.edit().putLong("device_call_log_sync_watermark_ms", epochMs).apply()
    }

    /** Last [CrmContactDirectorySync.refreshPhoneLookup] (epoch ms). */
    fun getCrmContactDirectorySyncedAt(): Long =
        prefs.getLong("crm_contact_directory_synced_at", 0L)

    fun setCrmContactDirectorySyncedAt(epochMs: Long) {
        prefs.edit().putLong("crm_contact_directory_synced_at", epochMs).apply()
    }

    fun isWhatsAppCallTrackingEnabled(): Boolean =
        prefs.getBoolean("whatsapp_call_tracking_enabled", true)

    fun setWhatsAppCallTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("whatsapp_call_tracking_enabled", enabled).apply()
    }

    fun isWhatsAppPostCallNotificationsEnabled(): Boolean =
        prefs.getBoolean(
            "whatsapp_post_call_notifications_enabled",
            Config.WHATSAPP_POST_CALL_NOTIFICATIONS_ENABLED,
        )

    fun setWhatsAppPostCallNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("whatsapp_post_call_notifications_enabled", enabled).apply()
    }

    /** Set once WhatsApp accessibility service is enabled — suppresses repeat setup prompts. */
    fun isWhatsAppAccessibilityServiceEnabled(): Boolean =
        prefs.getBoolean("whatsapp_a11y_service_enabled", false)

    fun setWhatsAppAccessibilityServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("whatsapp_a11y_service_enabled", enabled).apply()
    }

    /** Saved overlay X position in px (-1 = use default). */
    fun getCallNoteOverlayX(): Int = prefs.getInt("call_note_overlay_x", -1)

    /** Saved overlay Y position in px (-1 = use default). */
    fun getCallNoteOverlayY(): Int = prefs.getInt("call_note_overlay_y", -1)

    fun setCallNoteOverlayPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt("call_note_overlay_x", x)
            .putInt("call_note_overlay_y", y)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
