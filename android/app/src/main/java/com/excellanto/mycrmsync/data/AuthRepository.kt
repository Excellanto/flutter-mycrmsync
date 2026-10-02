package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.api.AuthApiService
import com.excellanto.mycrmsync.data.model.AuthVerifyData
import com.excellanto.mycrmsync.data.model.EmailOtpSendRequest
import com.excellanto.mycrmsync.data.model.EmailOtpVerifyRequest
import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object AuthRepository {

    private val api: AuthApiService by lazy { AuthApiService.create() }

    suspend fun sendEmailOtp(email: String): Result<String> = runAuthCall {
        val response = api.sendEmailOtp(EmailOtpSendRequest(email.trim()))
        if (response.success) {
            response.message
        } else {
            throw Exception(response.message)
        }
    }

    suspend fun verifyEmailOtp(email: String, code: String): Result<AuthVerifyData> = runAuthCall {
        val response = api.verifyEmailOtp(
            EmailOtpVerifyRequest(email = email.trim(), otp = code.trim()),
        )
        if (response.success && response.data != null) {
            response.data
        } else {
            throw Exception(
                response.message.takeIf { it.isNotBlank() }
                    ?: "Invalid login code.",
            )
        }
    }

    private inline fun <T> runAuthCall(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(Exception(toUserMessage(e)))
    }

    private fun toUserMessage(e: Exception): String {
        val network = e is IOException && e !is HttpException
        if (network || e.cause is ConnectException || e.cause is UnknownHostException) {
            return "Cannot reach server at ${Config.AUTH_API_BASE_URL.trimEnd('/')}. " +
                "Use your PC's Wi‑Fi IP on a physical phone (same network), or 10.0.2.2 on the emulator."
        }
        if (e is SocketTimeoutException) {
            return "Server timed out. Check that the API is running on your computer."
        }
        if (e is HttpException) {
            return e.parseApiMessage() ?: "Request failed (${e.code()})"
        }
        return e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }

    private fun HttpException.parseApiMessage(): String? {
        val body = response()?.errorBody()?.string().orEmpty()
        if (body.isBlank()) return null
        return runCatching {
            JsonParser.parseString(body)
                .asJsonObject
                .get("message")
                ?.asString
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
