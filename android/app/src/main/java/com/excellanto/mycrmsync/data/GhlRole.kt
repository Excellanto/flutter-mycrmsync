package com.excellanto.mycrmsync.data

/**
 * Maps GHL user `type` / `role` strings to app-level admin vs staff.
 * HighLevel uses several labels; we treat broad "admin-like" values as admin.
 */
object GhlRole {
    private val ADMIN_EXACT = setOf(
        "admin",
        "account", // common for sub-account / location admin
        "account_admin",
        "location_admin",
        "owner",
        "super_admin",
        "agency",
        "agency_admin",
    )

    private val STAFF_EXACT = setOf(
        "user",
        "read_only",
        "readonly",
        "staff",
    )

    fun isAdmin(raw: String?): Boolean {
        val t = raw?.trim()?.lowercase() ?: return false
        if (t.isEmpty()) return false
        if (t in STAFF_EXACT) return false
        if (t in ADMIN_EXACT) return true
        if (t.contains("admin")) return true
        if (t.contains("owner")) return true
        return false
    }
}
