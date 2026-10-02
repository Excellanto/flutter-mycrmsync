package com.excellanto.mycrmsync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.excellanto.mycrmsync.data.PrefsManager
import java.util.concurrent.TimeUnit

/** Enqueues call-log registration sync (immediate + periodic catch-up). */
object CallLogSyncScheduler {

    private const val IMMEDIATE_WORK = "callSync"
    private const val WHATSAPP_WORK = "whatsappCallSync"
    private const val PERIODIC_WORK = "callLogPeriodicSync"
    private const val OEM_RECORDING_WORK = "oemRecordingSync"

    /** After a call ends — queues locally, uploads when possible. */
    fun enqueueAfterCall(context: Context) {
        if (PrefsManager(context).getRegisterUserId() == null) return

        val request =
            OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.APPEND, request)

        enqueueOemRecordingSyncDelays(context)
    }

    /** After a WhatsApp call ends — register with CRM. */
    fun enqueueAfterWhatsAppCall(context: Context) {
        if (PrefsManager(context).getRegisterUserId() == null) return

        val request =
            OneTimeWorkRequestBuilder<WhatsAppCallSyncWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WHATSAPP_WORK, ExistingWorkPolicy.APPEND, request)
    }

    /** App open, pull-to-refresh, login — drain outbox soon. */
    fun enqueueNow(context: Context) {
        if (PrefsManager(context).getRegisterUserId() == null) return

        val request =
            OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.APPEND, request)

        val whatsAppRequest =
            OneTimeWorkRequestBuilder<WhatsAppCallSyncWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WHATSAPP_WORK, ExistingWorkPolicy.APPEND, whatsAppRequest)
    }

    /** Every 15 minutes when network is available. */
    fun schedulePeriodicIfLoggedIn(context: Context) {
        if (!PrefsManager(context).isLoggedIn()) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(PERIODIC_WORK)
            return
        }

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val periodic =
            PeriodicWorkRequestBuilder<CallLogPeriodicSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1,
                    TimeUnit.MINUTES,
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
    }

    /** OEM dialers often flush recordings seconds after hang-up — retry discovery. */
    private fun enqueueOemRecordingSyncDelays(context: Context) {
        val delaysSec = longArrayOf(8L, 20L, 45L)
        for (delaySec in delaysSec) {
            val request =
                OneTimeWorkRequestBuilder<OemRecordingSyncWorker>()
                    .setInitialDelay(delaySec, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "$OEM_RECORDING_WORK-$delaySec",
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }
    }

    fun enqueueOemRecordingSyncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<OemRecordingSyncWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(OEM_RECORDING_WORK, ExistingWorkPolicy.APPEND, request)
    }
}
