package com.excellanto.mycrmsync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.excellanto.mycrmsync.data.CallLogRegistrationSync
import com.excellanto.mycrmsync.data.PendingCallLogSyncException
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.oem.OemCallRecordingSyncService

class CallSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (PrefsManager(applicationContext).getRegisterUserId() == null) {
            Log.d(TAG, "Skip call sync: no user_id")
            return Result.success()
        }
        val res = CallLogRegistrationSync(applicationContext).sync()
        runCatching {
            OemCallRecordingSyncService.instance.syncOnce(
                applicationContext,
                CrmDatabase.getInstance(applicationContext),
            )
        }.onFailure { e ->
            Log.w(TAG, "OEM recording sync after call failed: ${e.message}")
        }
        return res.fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                val pending = (e as? PendingCallLogSyncException)?.pending
                Log.w(
                    TAG,
                    "Call sync incomplete${pending?.let { " ($it pending)" } ?: ""}: ${e.message}",
                )
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            },
        )
    }

    companion object {
        private const val TAG = "CallSyncWorker"
        private const val MAX_ATTEMPTS = 8
    }
}
