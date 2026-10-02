package com.excellanto.mycrmsync.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.excellanto.mycrmsync.data.audio.WaveformPeaksLoader
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

data class RecordingQueueItem(
    val uri: Uri,
    val contactName: String,
    val phone: String,
    val recordedAtIso: String?,
    val androidCallLogId: Long?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingPlayerBottomSheet(
    queue: List<RecordingQueueItem>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onDeleteRecording: (androidCallLogId: Long, onFinished: () -> Unit) -> Unit,
) {
    if (queue.isEmpty()) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var index by remember(startIndex, queue) { mutableIntStateOf(startIndex.coerceIn(queue.indices)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        RecordingPlayerSheetContent(
            queue = queue,
            index = index,
            onIndexChange = { index = it },
            onDismiss = onDismiss,
            onDeleteRecording = onDeleteRecording,
        )
    }
}

@Composable
private fun RecordingPlayerSheetContent(
    queue: List<RecordingQueueItem>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDeleteRecording: (androidCallLogId: Long, onFinished: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val item = queue[index]

    val player = remember {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    DisposableEffect(item.uri) {
        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
        player.playWhenReady = true
        onDispose { }
    }

    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(speed, item.uri) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, item.uri) {
        while (isActive) {
            positionMs = player.currentPosition
            val raw = player.duration
            durationMs = if (raw == C.TIME_UNSET || raw < 0) 0L else raw
            delay(if (player.isPlaying) 120L else 400L)
        }
    }

    var peaks by remember(item.uri) { mutableStateOf<FloatArray?>(null) }
    var peaksLoading by remember(item.uri) { mutableStateOf(true) }
    var peaksError by remember(item.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        peaksLoading = true
        peaksError = null
        peaks = null
        WaveformPeaksLoader.loadNormalizedPeaks(context, item.uri, WaveformPeaksLoader.DEFAULT_BARS)
            .onSuccess {
                peaks = it
                peaksLoading = false
            }
            .onFailure { e ->
                peaksLoading = false
                peaksError = e.message ?: "Waveform unavailable"
            }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete recording?") },
            text = {
                Text(
                    "This removes the audio file from your device and unlinks it from this call.",
                    color = TextSec,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        val id = item.androidCallLogId
                        if (id == null) onDismiss()
                        else onDeleteRecording(id, onDismiss)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp, max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            item.contactName.ifBlank { "Unknown contact" },
            color = TextMain,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            item.phone.ifBlank { "—" },
            color = TextSec,
            fontSize = 14.sp,
        )
        Text(
            formatRecordedAt(item.recordedAtIso),
            color = TextSec,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        PlaybackSpeedRow(
            current = speed,
            onSpeed = { speed = it },
        )

        Spacer(Modifier.height(12.dp))

        when {
            peaksLoading -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = PrimaryBlue,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Preparing waveform…", color = TextSec, fontSize = 13.sp)
                }
            }
            peaksError != null -> {
                Text(
                    "Waveform unavailable — playback still works.",
                    color = TextSec,
                    fontSize = 13.sp,
                )
                Text(peaksError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            peaks != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .pointerInput(durationMs, item.uri, player) {
                            val w = size.width
                            fun seekFraction(fx: Float) {
                                if (durationMs <= 0L) return
                                val f = fx.coerceIn(0f, 1f)
                                player.seekTo((f * durationMs).roundToLong().coerceIn(0L, durationMs))
                            }
                            detectTapGestures { offset -> seekFraction(offset.x / w) }
                        }
                        .pointerInput(durationMs, item.uri, player) {
                            val w = size.width
                            detectDragGestures { change, _ ->
                                if (durationMs <= 0L || w <= 0f) return@detectDragGestures
                                val f = (change.position.x / w).coerceIn(0f, 1f)
                                player.seekTo((f * durationMs).roundToLong().coerceIn(0L, durationMs))
                            }
                        },
                ) {
                    WaveformBarCanvas(
                        peaks = peaks!!,
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        played = PrimaryBlue,
                        unplayed = TextSec.copy(alpha = 0.35f),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Slider(
            value = positionMs.toFloat().coerceAtLeast(0f),
            onValueChange = { v ->
                val pos = v.roundToLong().coerceIn(0L, durationMs.coerceAtLeast(1L))
                player.seekTo(pos)
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatClock(positionMs),
                color = TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                formatClock(durationMs),
                color = TextSec,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (queue.size > 1) {
                IconButton(
                    onClick = {
                        onIndexChange((index - 1).coerceAtLeast(0))
                    },
                    enabled = index > 0,
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous recording", tint = TextMain)
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (isPlaying) player.pause() else player.play()
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                    tint = PrimaryBlue,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (queue.size > 1) {
                IconButton(
                    onClick = {
                        onIndexChange((index + 1).coerceAtMost(queue.lastIndex))
                    },
                    enabled = index < queue.lastIndex,
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next recording", tint = TextMain)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(
                onClick = {
                    val err = shareRecordingFile(context, item.uri)
                    if (err != null) {
                        /* no Toast dependency; user sees chooser or failure is rare */
                    }
                },
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share", color = PrimaryBlue)
            }
            if (item.androidCallLogId != null) {
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PlaybackSpeedRow(
    current: Float,
    onSpeed: (Float) -> Unit,
) {
    val options = listOf(0.5f, 0.75f, 1f, 1.5f, 2f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { s ->
            val selected = kotlin.math.abs(current - s) < 0.01f
            TextButton(
                onClick = { onSpeed(s) },
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) PrimaryBlue.copy(alpha = 0.12f) else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                Text(
                    "${trimSpeedLabel(s)}×",
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) PrimaryBlue else TextSec,
                )
            }
        }
    }
}

private fun trimSpeedLabel(s: Float): String {
    return when (s) {
        0.5f -> "0.5"
        0.75f -> "0.75"
        1f -> "1"
        1.5f -> "1.5"
        2f -> "2"
        else -> s.toString()
    }
}

@Composable
private fun WaveformBarCanvas(
    peaks: FloatArray,
    progress: Float,
    modifier: Modifier,
    played: Color,
    unplayed: Color,
) {
    Canvas(modifier = modifier) {
        val n = peaks.size
        if (n == 0) return@Canvas
        val barW = size.width / n
        val gap = (barW * 0.2f).coerceAtMost(3f)
        val w = (barW - gap).coerceAtLeast(1f)
        val splitX = size.width * progress
        for (i in 0 until n) {
            val h = size.height * peaks[i].coerceIn(0.05f, 1f)
            val left = i * barW + gap / 2
            val top = size.height - h
            val color = if (left + w / 2 <= splitX) played else unplayed
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
        // progress line
        drawLine(
            color = played.copy(alpha = 0.9f),
            start = Offset(splitX, 0f),
            end = Offset(splitX, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

private fun formatClock(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun formatRecordedAt(iso: String?): String {
    if (iso.isNullOrBlank()) return "Recorded time unknown"
    return runCatching {
        val odt = OffsetDateTime.parse(iso)
        val z = odt.atZoneSameInstant(ZoneId.systemDefault())
        z.format(DateTimeFormatter.ofPattern("EEE, MMM d · hh:mm a", Locale.US))
    }.getOrElse { "Recorded time unknown" }
}

private fun shareRecordingFile(context: android.content.Context, uri: Uri): String? {
    return try {
        val stream: Uri = when (uri.scheme?.lowercase()) {
            "content" -> uri
            "file" -> {
                val path = uri.path ?: return "Missing file path."
                val f = File(path)
                if (!f.exists()) return "Recording file not found."
                val dest = File(context.cacheDir, "share_audio_${f.name}")
                f.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    dest,
                )
            }
            else -> return "Sharing is not available for this source."
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(stream) ?: "audio/*"
            putExtra(Intent.EXTRA_STREAM, stream)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share recording"))
        null
    } catch (e: Exception) {
        e.localizedMessage
    }
}
