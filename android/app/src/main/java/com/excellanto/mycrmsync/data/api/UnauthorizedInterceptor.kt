package com.excellanto.mycrmsync.data.api

import com.excellanto.mycrmsync.data.auth.AuthSessionManager
import okhttp3.Interceptor
import okhttp3.Response

class UnauthorizedInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 401) {
            val path = request.url.encodedPath
            val isAuthEndpoint = path.contains("/api/auth/")
            val sentAuthorization = !request.header("Authorization").isNullOrBlank()
            if (!isAuthEndpoint && sentAuthorization) {
                AuthSessionManager.onUnauthorized()
            }
        }

        return response
    }
}
