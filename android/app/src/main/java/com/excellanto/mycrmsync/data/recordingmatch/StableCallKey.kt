package com.excellanto.mycrmsync.data.recordingmatch

import java.time.OffsetDateTime

internal fun parseStartedAtMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}
