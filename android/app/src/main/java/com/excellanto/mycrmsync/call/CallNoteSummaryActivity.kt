package com.excellanto.mycrmsync.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.excellanto.mycrmsync.data.ContactNoteSummariesLoader
import com.excellanto.mycrmsync.data.ContactNoteSummariesResult
import com.excellanto.mycrmsync.data.model.CrmContactNoteSummariesData
import com.excellanto.mycrmsync.ui.screens.CallNoteSummaryOverlay
import com.excellanto.mycrmsync.ui.theme.CRM_CALL_SYNCTheme
import kotlinx.coroutines.launch

class CallNoteSummaryActivity : ComponentActivity() {
    private var dismissReceiverRegistered = false

    private val dismissReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                finish()
            }
        }

    private var phone by mutableStateOf("")
    private var contactId: String? = null
    private var contactName: String? = null
    private var direction by mutableStateOf(CallNoteSummaryLauncher.DIRECTION_INCOMING)
    private var isLoading by mutableStateOf(true)
    private var summariesData by mutableStateOf<CrmContactNoteSummariesData?>(null)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowForCallOverlay()
        readIntentExtras(intent)
        registerDismissReceiver()
        loadSummaries()

        enableEdgeToEdge()
        setContent {
            CRM_CALL_SYNCTheme {
                CallNoteSummaryOverlay(
                    phone = phone,
                    direction = direction,
                    isLoading = isLoading,
                    data = summariesData,
                    errorMessage = errorMessage,
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntentExtras(intent)
        loadSummaries()
    }

    override fun onDestroy() {
        unregisterDismissReceiver()
        super.onDestroy()
    }

    private fun configureWindowForCallOverlay() {
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun readIntentExtras(intent: Intent?) {
        phone = intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_PHONE).orEmpty()
        contactId =
            intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_CONTACT_ID)?.trim()?.ifBlank { null }
        contactName =
            intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_CONTACT_NAME)?.trim()?.ifBlank { null }
        direction =
            intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_DIRECTION)
                ?: CallNoteSummaryLauncher.DIRECTION_INCOMING
    }

    private fun loadSummaries() {
        val targetPhone = phone.trim()
        if (targetPhone.isEmpty() && contactId.isNullOrBlank()) {
            finish()
            return
        }

        isLoading = true
        errorMessage = null
        summariesData = null

        lifecycleScope.launch {
            when (
                val result =
                    ContactNoteSummariesLoader.load(
                        this@CallNoteSummaryActivity,
                        targetPhone,
                        contactId,
                        contactName,
                    )
            ) {
                is ContactNoteSummariesResult.Success -> {
                    summariesData = result.data
                    isLoading = false
                    if (result.data.contact == null && result.data.summaries.isEmpty()) {
                        finish()
                    }
                }
                is ContactNoteSummariesResult.Error -> {
                    errorMessage = result.message
                    isLoading = false
                    finish()
                }
                ContactNoteSummariesResult.Skipped -> finish()
            }
        }
    }

    private fun registerDismissReceiver() {
        if (dismissReceiverRegistered) return
        val filter = IntentFilter(CallNoteSummaryLauncher.ACTION_DISMISS)
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        dismissReceiverRegistered = true
    }

    private fun unregisterDismissReceiver() {
        if (!dismissReceiverRegistered) return
        unregisterReceiver(dismissReceiver)
        dismissReceiverRegistered = false
    }
}
