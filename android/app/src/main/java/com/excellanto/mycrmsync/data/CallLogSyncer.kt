package com.excellanto.mycrmsync.data

import android.content.Context

/**
 * Registers device CallLog rows with the CRM backend via [CallLogRegistrationSync] outbox.
 */
class CallLogSyncer(
    private val context: Context,
) {
  suspend fun syncLatestCall(): Result<Unit> = CallLogRegistrationSync(context).sync()

  suspend fun syncIncrementalDeviceCalls(): Result<Unit> = syncLatestCall()
}
