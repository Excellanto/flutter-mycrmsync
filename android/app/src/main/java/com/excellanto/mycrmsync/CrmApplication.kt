package com.excellanto.mycrmsync

import android.app.Application
import com.excellanto.mycrmsync.call.PhoneCallMonitor
import com.excellanto.mycrmsync.call.PhoneCallWatcher
import com.excellanto.mycrmsync.data.auth.AuthSessionManager
import com.excellanto.mycrmsync.work.CallLogSyncScheduler

class CrmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthSessionManager.init(this)
        CallLogSyncScheduler.schedulePeriodicIfLoggedIn(this)
        PhoneCallWatcher.ensureListening(this)
        PhoneCallMonitor.start(this)
        // Initialize Intercom
        // Intercom.initialize(this, "YOUR_API_KEY", "YOUR_APP_ID")
    }
}
