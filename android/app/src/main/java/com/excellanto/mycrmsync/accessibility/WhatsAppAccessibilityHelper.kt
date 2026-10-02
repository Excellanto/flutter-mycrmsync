package com.excellanto.mycrmsync.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.excellanto.mycrmsync.data.PrefsManager

object WhatsAppAccessibilityHelper {
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, WhatsAppCallAccessibilityService::class.java).flattenToString()
        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        return TextUtils.SimpleStringSplitter(':').let { splitter ->
            splitter.setString(enabledServices)
            splitter.any { it.equals(expected, ignoreCase = true) }
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled
    }

    /** Persists enabled state so the app does not re-prompt after the user turns the service on. */
    fun rememberIfEnabled(context: Context): Boolean {
        val enabled = isServiceEnabled(context)
        if (enabled) {
            PrefsManager(context.applicationContext).setWhatsAppAccessibilityServiceEnabled(true)
        }
        return enabled
    }

    fun shouldShowSetupPrompt(context: Context): Boolean {
        val prefs = PrefsManager(context.applicationContext)
        if (prefs.isWhatsAppAccessibilityServiceEnabled()) return false
        return !rememberIfEnabled(context)
    }
}
