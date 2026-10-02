package com.excellanto.mycrmsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.excellanto.mycrmsync.call.PhoneCallMonitor

class PhoneCallMonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        PhoneCallMonitor.start(context)
    }
}
