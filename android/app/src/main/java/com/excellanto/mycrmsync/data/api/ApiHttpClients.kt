package com.excellanto.mycrmsync.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

object ApiHttpClients {
    fun authenticated(): OkHttpClient {
        val logger =
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        return OkHttpClient.Builder()
            .addInterceptor(logger)
            .addInterceptor(UnauthorizedInterceptor())
            .build()
    }

    fun publicApi(): OkHttpClient {
        val logger =
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        return OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()
    }
}
