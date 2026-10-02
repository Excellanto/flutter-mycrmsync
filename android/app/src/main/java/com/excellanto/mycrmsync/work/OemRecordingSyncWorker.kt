package com.excellanto.mycrmsync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.oem.OemCallRecordingSyncService

/** Discovers OEM call recordings and links them to mirrored system call-log rows. */
class OemRecordingSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            OemCallRecordingSyncService.instance.syncOnce(
                applicationContext,
                CrmDatabase.getInstance(applicationContext),
            )
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "OEM recording sync failed: ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "OemRecordingSyncWorker"
        private const val MAX_ATTEMPTS = 3
    }
}
