package com.excellanto.mycrmsync.call

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.excellanto.mycrmsync.ui.screens.WhatsAppContactMatchCard
import com.excellanto.mycrmsync.ui.theme.CRM_CALL_SYNCTheme

/** Opened from the match notification when overlay permission is not granted. */
class WhatsAppContactMatchActivity : ComponentActivity() {
    private var session by mutableStateOf<WhatsAppContactMatchSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        bindSession(intent)
        setContent {
            val current = session
            CRM_CALL_SYNCTheme {
                Box(modifier = Modifier.padding(16.dp)) {
                    if (current != null) {
                        WhatsAppContactMatchCard(
                            direction = current.direction,
                            displayName = current.displayName,
                            isLoading = current.isLoading,
                            matches = current.matches,
                            errorMessage = current.errorMessage,
                            linkingId = current.linkingId,
                            selectedId = current.selectedId,
                            onSelect = current::choose,
                            onSave = { current.save { finish() } },
                            onDismiss = { finish() },
                            modifier = Modifier.fillMaxWidth().height(420.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindSession(intent)
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun bindSession(intent: Intent?) {
        val displayName = intent?.getStringExtra(WhatsAppContactMatchPresenter.EXTRA_DISPLAY_NAME).orEmpty()
        val phone = intent?.getStringExtra(WhatsAppContactMatchPresenter.EXTRA_PHONE)
        val direction =
            intent?.getStringExtra(WhatsAppContactMatchPresenter.EXTRA_DIRECTION) ?: "OUTGOING"
        val startedAt = intent?.getLongExtra(WhatsAppContactMatchPresenter.EXTRA_STARTED_AT, 0L) ?: 0L
        if (displayName.isBlank() && phone.isNullOrBlank()) {
            finish()
            return
        }
        session?.close()
        session =
            WhatsAppContactMatchSession(
                context = this,
                displayName = displayName.ifBlank { phone.orEmpty() },
                phoneRaw = phone,
                direction = direction,
                startedAtMs = startedAt,
            ).also { it.start {} }
    }

    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }
}
