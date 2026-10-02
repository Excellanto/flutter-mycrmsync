package com.excellanto.mycrmsync.data

/** Limits for reading and displaying device [android.provider.CallLog] rows. */
object DeviceCallLogLimits {
    /** First-time bootstrap: newest N calls from the system log. */
    const val INITIAL_BOOTSTRAP = 10

    /** Max new rows per incremental CRM sync (after bootstrap). */
    const val INCREMENTAL_SYNC_BATCH = 360

    /** Max new rows per incremental local Room mirror (after bootstrap). */
    const val LOCAL_MIRROR_INCREMENTAL = 100

    /** Max rows shown on the Call Logs screen (from API). */
    const val UI_LIST_MAX = 100

    /** Max rows fetched for dashboard KPIs (from API). */
    const val DASHBOARD_MAX = 500
}
