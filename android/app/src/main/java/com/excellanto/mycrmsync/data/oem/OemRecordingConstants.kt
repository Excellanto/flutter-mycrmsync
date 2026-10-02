package com.excellanto.mycrmsync.data.oem

object OemRecordingConstants {
    const val PRIMARY_WINDOW_SEC = 90L
    const val EXTENDED_WINDOW_SEC = 480L
    const val GREEDY_MIN_CONFIDENCE = 0.32
    const val SECONDARY_MIN_SCORE = 0.28
    const val SECONDARY_START_SLACK_SEC = 120L
    const val SECONDARY_END_SLACK_SEC = 600L
    const val MISSED_LONG_FILE_PENALTY_MS = 30_000L
    const val DIGIT_BONUS = 0.35
    const val NAME_BONUS = 0.12
    const val MISSED_LONG_FILE_SCORE_PENALTY = 0.15
    const val TIME_SCORE_DIVISOR = 45.0
    const val SECONDARY_FALLBACK_MULTIPLIER = 0.6
    const val MEDIA_STORE_MIN_SIZE_BYTES = 1024L
    const val DISCOVERY_MAX_ROWS_CAP_LOW = 1
    const val DISCOVERY_MAX_ROWS_CAP_HIGH = 500
    const val SYNC_TYPICAL_MAX_ROWS = 600
    const val WATERMARK_QUERY_SLACK_SEC = 300L
    /** Max MediaStore rows to scan (newest first) per discovery pass — avoids missing older call files. */
    const val DISCOVERY_MAX_MEDIA_STORE_SCAN_ROWS = 25_000
}
