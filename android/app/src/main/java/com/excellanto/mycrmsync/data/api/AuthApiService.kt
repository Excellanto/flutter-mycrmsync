package com.excellanto.mycrmsync.data.api

import com.excellanto.mycrmsync.data.Config
import com.excellanto.mycrmsync.data.model.AuthApiResponse
import com.excellanto.mycrmsync.data.model.AuthVerifyData
import com.excellanto.mycrmsync.data.model.EmailOtpSendRequest
import com.excellanto.mycrmsync.data.model.EmailOtpVerifyRequest
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {

    @POST("api/auth/email-otp/send")
    suspend fun sendEmailOtp(@Body request: EmailOtpSendRequest): AuthApiResponse<Any?>

    @POST("api/auth/email-otp/verify")
    suspend fun verifyEmailOtp(@Body request: EmailOtpVerifyRequest): AuthApiResponse<AuthVerifyData>

    companion object {
        fun create(): AuthApiService {
            return Retrofit.Builder()
                .baseUrl(Config.AUTH_API_BASE_URL)
                .client(ApiHttpClients.publicApi())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AuthApiService::class.java)
        }
    }
}
