package com.excellanto.mycrmsync.data

/**
 * Central configuration for hardcoded API keys and credentials.
 */
object Config {
    /**
     * Backend auth API (email OTP).
     * - **Physical phone** (same Wi‑Fi as dev machine): your PC LAN IP, e.g. `http://192.168.29.183:8000/`
     * - **Android emulator**: `http://10.0.2.2:8000/` (maps to host `127.0.0.1`)
     */
        const val AUTH_API_BASE_URL = "https://app.mycrmsync.in/"

    /**
     * Default for WhatsApp post-call **system notifications** (background only).
     * In-app post-call modal is unaffected. Override per device via
     * [PrefsManager.setWhatsAppPostCallNotificationsEnabled].
     */
    const val WHATSAPP_POST_CALL_NOTIFICATIONS_ENABLED = false
}
