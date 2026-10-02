package com.excellanto.mycrmsync.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.accessibility.WhatsAppAccessibilityHelper
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.ErrorRed
import com.excellanto.mycrmsync.ui.theme.LightBg
import com.excellanto.mycrmsync.ui.theme.SuccessGreen
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppSetupScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager(context) }
    var serviceEnabled by remember { mutableStateOf(WhatsAppAccessibilityHelper.isServiceEnabled(context)) }
    var trackingEnabled by remember { mutableStateOf(prefs.isWhatsAppCallTrackingEnabled()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    serviceEnabled = WhatsAppAccessibilityHelper.rememberIfEnabled(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = LightBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.whatsapp_setup_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_whatsapp),
                            contentDescription = null,
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            stringResource(R.string.whatsapp_setup_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextMain,
                        )
                    }
                    Text(
                        stringResource(R.string.whatsapp_setup_description),
                        color = TextSec,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (serviceEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (serviceEnabled) SuccessGreen else ErrorRed,
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (serviceEnabled) {
                                stringResource(R.string.whatsapp_setup_status_enabled)
                            } else {
                                stringResource(R.string.whatsapp_setup_status_disabled)
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = TextMain,
                        )
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Track WhatsApp calls", fontWeight = FontWeight.Medium, color = TextMain)
                    Text("Save metadata when accessibility is enabled", color = TextSec, fontSize = 12.sp)
                }
                Switch(
                    checked = trackingEnabled,
                    onCheckedChange = {
                        trackingEnabled = it
                        prefs.setWhatsAppCallTrackingEnabled(it)
                    },
                )
            }

            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.whatsapp_setup_enable))
            }
        }
    }
}
