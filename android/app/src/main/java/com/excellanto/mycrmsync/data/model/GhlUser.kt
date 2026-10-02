package com.excellanto.mycrmsync.data.model

import com.google.gson.annotations.SerializedName

data class GhlUserResponse(
    val users: List<GhlUser>
)

/**
 * GHL users list / user object.
 *
 * HighLevel is inconsistent across API versions:
 *  - Some responses expose `type` / `role` at the top level.
 *  - The v2 (services.leadconnectorhq.com) `/users/` response nests them inside a
 *    `roles` object: `{ "roles": { "type": "account", "role": "admin" } }`.
 *
 * [effectiveType] normalizes all of these so the rest of the app can treat role
 * detection uniformly.
 */
data class GhlUser(
    val id: String,
    val name: String,
    val email: String,
    @SerializedName("type") val type: String? = null,
    @SerializedName("role") val role: String? = null,
    @SerializedName("roles") val roles: GhlUserRoles? = null,
    @SerializedName("permissions") val permissions: Map<String, Any?>? = null,
    @SerializedName("profilePhoto") val profilePhoto: String? = null,
) {
    /**
     * First non-blank value across the known shapes:
     *   top-level `role` → top-level `type` → `roles.role` → `roles.type`.
     *
     * We prefer `role` over `type` because v2 returns e.g. `type="account"` (the
     * scope) and `role="admin"` (the actual permission tier).
     */
    fun effectiveType(): String? = listOfNotNull(
        role?.trim()?.takeIf { it.isNotEmpty() },
        type?.trim()?.takeIf { it.isNotEmpty() },
        roles?.role?.trim()?.takeIf { it.isNotEmpty() },
        roles?.type?.trim()?.takeIf { it.isNotEmpty() },
    ).firstOrNull()
}

/** Nested object from GHL v2 `/users/`. */
data class GhlUserRoles(
    @SerializedName("type") val type: String? = null,
    @SerializedName("role") val role: String? = null,
    @SerializedName("locationIds") val locationIds: List<String>? = null,
)