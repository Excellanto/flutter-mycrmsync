package com.excellanto.mycrmsync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.excellanto.mycrmsync.data.CallLogRegistrationSync
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.WhatsAppCallRegistrationSync
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.oem.OemCallRecordingSyncService

/** Periodic catch-up for pending call log registrations (requires network). */
class CallLogPeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (PrefsManager(applicationContext).getRegisterUserId() == null) {
            return Result.success()
        }
        val res = CallLogRegistrationSync(applicationContext).sync()
        runCatching { WhatsAppCallRegistrationSync(applicationContext).sync() }
        runCatching {
            OemCallRecordingSyncService.instance.syncOnce(
                applicationContext,
                CrmDatabase.getInstance(applicationContext),
            )
        }.onFailure { e ->
            Log.w(TAG, "OEM recording sync periodic failed: ${e.message}")
        }
        return res.fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                Log.w(TAG, "Periodic call log sync incomplete: ${e.message}")
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            },
        )
    }

    companion object {
        private const val TAG = "CallLogPeriodicSync"
        private const val MAX_ATTEMPTS = 5
    }
}
