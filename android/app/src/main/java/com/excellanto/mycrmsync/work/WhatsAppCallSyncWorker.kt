package com.excellanto.mycrmsync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.excellanto.mycrmsync.data.PendingWhatsAppCallSyncException
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.WhatsAppCallRegistrationSync

class WhatsAppCallSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (PrefsManager(applicationContext).getRegisterUserId() == null) {
            Log.d(TAG, "Skip WhatsApp sync: no user_id")
            return Result.success()
        }
        return WhatsAppCallRegistrationSync(applicationContext).sync().fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                val pending = (e as? PendingWhatsAppCallSyncException)?.pending
                Log.w(
                    TAG,
                    "WhatsApp sync incomplete${pending?.let { " ($it pending)" } ?: ""}: ${e.message}",
                )
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            },
        )
    }

    companion object {
        private const val TAG = "WhatsAppCallSyncWorker"
        private const val MAX_ATTEMPTS = 8
    }
}
