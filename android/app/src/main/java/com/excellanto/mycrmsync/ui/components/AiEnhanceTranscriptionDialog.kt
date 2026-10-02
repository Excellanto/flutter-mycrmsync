package com.excellanto.mycrmsync.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.excellanto.mycrmsync.data.model.CallRecordingSentiment
import com.excellanto.mycrmsync.data.model.CallRecordingTranscribeData
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.ErrorRed
import com.excellanto.mycrmsync.ui.theme.InputBg
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec
import com.excellanto.mycrmsync.ui.viewmodel.AiEnhanceUiState
import kotlinx.coroutines.delay

@Composable
fun AiEnhanceTranscriptionDialog(
    state: AiEnhanceUiState,
    onDismissRequest: () -> Unit,
    onUpdateToNotes: () -> Unit,
) {
    if (state is AiEnhanceUiState.Hidden) return

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(24.dp)
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    is AiEnhanceUiState.Transcribing -> TranscribingContent(state.contactName)
                    is AiEnhanceUiState.Ready ->
                        ReadyContent(
                            result = state.result,
                            onUpdateToNotes = onUpdateToNotes,
                            onDismiss = onDismissRequest,
                        )
                    is AiEnhanceUiState.Failed -> FailedContent(state.message, onDismissRequest)
                    is AiEnhanceUiState.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun TranscribingContent(contactName: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "AI Enhance",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextMain,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            contactName,
            fontSize = 13.sp,
            color = TextSec,
        )
        Spacer(Modifier.height(20.dp))
        PulsingProgress()
        Spacer(Modifier.height(16.dp))
        AnimatedTranscriptionLabel()
    }
}

@Composable
private fun PulsingProgress() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "scale",
    )
    CircularProgressIndicator(
        modifier =
            Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        color = PrimaryBlue,
        strokeWidth = 4.dp,
    )
}

@Composable
private fun AnimatedTranscriptionLabel() {
    var dotCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(350)
            dotCount = (dotCount + 1) % 4
        }
    }
    Text(
        text = "Processing call recording" + ".".repeat(dotCount),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = PrimaryBlue,
    )
}

@Composable
private fun ReadyContent(
    result: CallRecordingTranscribeData,
    onUpdateToNotes: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text(
        "Call Recording Analysis",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = TextMain,
    )

    val metaParts =
        buildList {
            result.transcriptionEngineLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            result.durationSec?.let { sec ->
                val m = sec / 60
                val s = sec % 60
                val label = if (m > 0) "${m}:${s.toString().padStart(2, '0')}" else "${s}s"
                add(label)
            }
            result.status?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
        }
    if (metaParts.isNotEmpty()) {
        Text(
            metaParts.joinToString(" · "),
            fontSize = 11.sp,
            color = TextSec,
        )
    }

    result.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { summary ->
        ResultSection(title = "Summary", body = summary)
    }

    ResultSection(title = "Transcription", body = result.transcription)

    result.sentiment?.let { sentiment ->
        SentimentSection(sentiment)
    }

    val recordingMeta =
        buildList {
            result.fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { add("File: $it") }
            result.recordingUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Recording URL: $it") }
        }
    if (recordingMeta.isNotEmpty()) {
        ResultSection(title = "Recording", body = recordingMeta.joinToString("\n"))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onUpdateToNotes,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update to Notes")
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Close", color = TextSec)
        }
    }
}

@Composable
private fun ResultSection(
    title: String,
    body: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(InputBg, RoundedCornerShape(10.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryBlue,
        )
        Text(
            body,
            fontSize = 13.sp,
            color = TextMain,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun SentimentSection(sentiment: CallRecordingSentiment) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(InputBg, RoundedCornerShape(10.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Sentiment",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryBlue,
        )

        sentiment.overall?.trim()?.takeIf { it.isNotEmpty() }?.let { overall ->
            SentimentRow(
                label = "Overall",
                value = overall.replaceFirstChar { it.uppercase() },
                detail = formatScoreDetail(sentiment.score, sentiment.confidence),
            )
        }

        sentiment.customer?.let { customer ->
            SentimentRow(
                label = "Customer",
                value = customer.label?.replaceFirstChar { it.uppercase() } ?: "—",
                detail = formatScore(customer.score),
            )
        }

        sentiment.agent?.let { agent ->
            SentimentRow(
                label = "Agent",
                value = agent.label?.replaceFirstChar { it.uppercase() } ?: "—",
                detail = formatScore(agent.score),
            )
        }

        sentiment.highlights?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { highlights ->
            HorizontalDivider(color = CardWhite)
            Text(
                "Highlights",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain,
            )
            highlights.forEach { highlight ->
                Text(
                    "• $highlight",
                    fontSize = 13.sp,
                    color = TextMain,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun SentimentRow(
    label: String,
    value: String,
    detail: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 11.sp,
                color = TextSec,
            )
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextMain,
            )
        }
        detail?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = TextSec,
            )
        }
    }
}

private fun formatScore(score: Double?): String? {
    score ?: return null
    val pct = (score * 100).toInt()
    val sign = if (pct >= 0) "+" else ""
    return "$sign$pct%"
}

private fun formatScoreDetail(score: Double?, confidence: Double?): String? {
    val scorePart = formatScore(score)
    val confidencePart =
        confidence?.let { conf ->
            "confidence ${(conf * 100).toInt()}%"
        }
    return when {
        scorePart != null && confidencePart != null -> "$scorePart · $confidencePart"
        scorePart != null -> scorePart
        confidencePart != null -> confidencePart
        else -> null
    }
}

@Composable
private fun FailedContent(
    message: String,
    onDismiss: () -> Unit,
) {
    Icon(
        Icons.Outlined.ErrorOutline,
        contentDescription = null,
        tint = ErrorRed,
        modifier = Modifier.size(40.dp),
    )
    Text(
        "Transcription failed",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = TextMain,
    )
    Text(
        message,
        fontSize = 13.sp,
        color = TextSec,
    )
    TextButton(onClick = onDismiss) {
        Text("Close", color = PrimaryBlue)
    }
}
