package com.excellanto.mycrmsync.data.model

import com.google.gson.annotations.SerializedName

data class EmailOtpSendRequest(val email: String)

data class EmailOtpVerifyRequest(
    val email: String,
    val otp: String,
)

data class AuthApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T?,
)

data class AuthVerifyData(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_at") val expiresAt: String,
    val user: AuthUser,
    @SerializedName("Integrated_system") val integratedSystem: IntegratedSystem? = null,
    @SerializedName("userrole") val userRole: String? = null,
)

data class AuthUser(
    val id: Int,
    val name: String,
    val email: String,
    @SerializedName("tenant_id") val tenantId: Int,
    val intsysuser: String? = null,
    @SerializedName("integrated_user") val integratedUser: IntegratedUser? = null,
    @SerializedName("userrole") val userRole: String? = null,
)

data class IntegratedUser(
    val id: String,
    val name: String,
    val email: String,
    val phone: String?,
    val role: String?,
)

data class IntegratedSystem(
    val slug: String,
    val name: String,
    val type: String,
    val documentation: String?,
    @SerializedName("integration_status") val integrationStatus: Boolean,
)
