package com.excellanto.mycrmsync.data.auth

import android.content.Context
import com.excellanto.mycrmsync.data.PrefsManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AuthSessionManager {
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private lateinit var appContext: Context

    @Volatile
    private var sessionCleared = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun resetAfterLogin() {
        sessionCleared = false
    }

    fun onUnauthorized() {
        if (sessionCleared || !::appContext.isInitialized) return
        sessionCleared = true
        PrefsManager(appContext).clearAll()
        _sessionExpired.tryEmit(Unit)
    }
}
