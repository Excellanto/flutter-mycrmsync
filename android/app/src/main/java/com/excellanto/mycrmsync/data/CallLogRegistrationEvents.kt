package com.excellanto.mycrmsync.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Notifies in-app UI when device call logs are registered with the CRM backend. */
object CallLogRegistrationEvents {
    private val _registrations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val registrations: SharedFlow<Unit> = _registrations.asSharedFlow()

    internal fun notifyRegistered() {
        _registrations.tryEmit(Unit)
    }
}
