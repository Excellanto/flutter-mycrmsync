package com.excellanto.mycrmsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.ErrorRed
import com.excellanto.mycrmsync.ui.theme.LightBg
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec
import com.excellanto.mycrmsync.ui.viewmodel.WhatsAppPostCallViewModel
import java.util.Locale

@Composable
fun WhatsAppPostCallScreen(
    syncFingerprint: String,
    onDismiss: () -> Unit,
    viewModel: WhatsAppPostCallViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(syncFingerprint) {
        viewModel.load(syncFingerprint)
    }

    Dialog(onDismissRequest = { viewModel.dismiss(onDismiss) }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ui.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = PrimaryBlue)
                    }
                    return@Column
                }

                val entity = ui.entity
                if (entity == null) {
                    Text(ui.error ?: "Call not found", color = ErrorRed)
                    TextButton(onClick = { viewModel.dismiss(onDismiss) }) {
                        Text(stringResource(R.string.whatsapp_post_call_skip))
                    }
                    return@Column
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F9EE)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_whatsapp),
                            contentDescription = null,
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.whatsapp_post_call_title),
                            fontWeight = FontWeight.Bold,
                            color = TextMain,
                            fontSize = 18.sp,
                        )
                        Text(
                            entity.contactName ?: entity.displayName,
                            color = TextSec,
                            fontSize = 14.sp,
                        )
                    }
                }

                val dir = entity.direction.uppercase(Locale.getDefault())
                val (dirIcon, dirLabel) =
                    when (dir) {
                        "INCOMING" -> Icons.Default.CallReceived to "Incoming"
                        "OUTGOING" -> Icons.Default.CallMade to "Outgoing"
                        "MISSED" -> Icons.Default.CallMissed to "Missed"
                        else -> Icons.Default.CallMade to dir
                    }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LightBg)
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(dirIcon, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(dirLabel, color = TextMain, fontWeight = FontWeight.Medium)
                    }
                    Text(formatDuration(entity.durationSec), color = PrimaryBlue, fontWeight = FontWeight.Bold)
                }

                Text(
                    entity.startedAtIso,
                    color = TextSec,
                    fontSize = 12.sp,
                )

                if (!ui.canUploadNotes) {
                    Text(
                        stringResource(R.string.whatsapp_post_call_no_contact),
                        color = ErrorRed,
                        fontSize = 12.sp,
                    )
                }

                OutlinedTextField(
                    value = ui.noteText,
                    onValueChange = viewModel::updateNoteText,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ui.canUploadNotes && !ui.isSaving,
                    placeholder = { Text("Add call notes…") },
                    minLines = 3,
                )

                if (ui.canUploadNotes) {
                    OutlinedButton(
                        onClick = viewModel::applyAiFormattedNote,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !ui.isSaving,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Insert call summary")
                    }
                }

                ui.error?.let { err ->
                    Text(err, color = ErrorRed, fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { viewModel.dismiss(onDismiss) },
                        modifier = Modifier.weight(1f),
                        enabled = !ui.isSaving,
                    ) {
                        Text(stringResource(R.string.whatsapp_post_call_skip))
                    }
                    Button(
                        onClick = { viewModel.saveNote(onDismiss) },
                        modifier = Modifier.weight(1f),
                        enabled = ui.canUploadNotes && !ui.isSaving,
                    ) {
                        if (ui.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Text(stringResource(R.string.whatsapp_post_call_add_notes))
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    val m = seconds / 60
    val s = seconds % 60
    return if (m <= 0) "${s}s" else "${m}:${s.toString().padStart(2, '0')}"
}
