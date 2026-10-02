package com.excellanto.mycrmsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.call.CallNoteSummaryLauncher
import com.excellanto.mycrmsync.data.model.CrmContactNoteSummariesData
import com.excellanto.mycrmsync.data.model.CrmNoteSummaryItem
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.LightIndigo
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CallNoteSummaryOverlay(
    phone: String,
    direction: String,
    isLoading: Boolean,
    data: CrmContactNoteSummariesData?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
) {
    val isIncoming = direction == CallNoteSummaryLauncher.DIRECTION_INCOMING
    val directionLabel =
        if (isIncoming) {
            stringResource(R.string.call_note_summary_incoming)
        } else {
            stringResource(R.string.call_note_summary_outgoing)
        }

    Card(
        modifier =
            Modifier
                .fillMaxSize()
                .shadow(12.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .background(Color(0xFFF8FAFC))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.call_note_summary_drag_hint),
                    tint = TextSec,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.call_note_summary_drag_hint),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    fontSize = 12.sp,
                    color = TextSec,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.call_note_summary_dismiss),
                        tint = TextSec,
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(LightIndigo),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector =
                                if (isIncoming) Icons.Default.CallReceived else Icons.Default.CallMade,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            text = directionLabel,
                            fontSize = 12.sp,
                            color = TextSec,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = data?.contact?.name ?: phone,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle =
                            data?.contact?.phone?.takeIf { it.isNotBlank() } ?: phone
                        Text(
                            text = subtitle,
                            fontSize = 13.sp,
                            color = TextSec,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryBlue,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    return@Column
                }

                if (errorMessage != null) {
                    Text(text = errorMessage, color = TextSec, fontSize = 14.sp)
                    return@Column
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Notes,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.call_note_summary_title),
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        fontSize = 15.sp,
                    )
                }

                val summaries = data?.summaries.orEmpty()
                if (summaries.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Text(
                            text = stringResource(R.string.call_note_summary_empty),
                            color = TextSec,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        summaries.forEachIndexed { index, item ->
                            CallNoteSummaryRow(item)
                            if (index < summaries.lastIndex) {
                                HorizontalDivider(color = Color(0xFFE2E8F0))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallNoteSummaryRow(item: CrmNoteSummaryItem) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        formatSummaryWhen(item)?.let { whenLabel ->
            Text(
                text = whenLabel,
                color = TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
            )
        }
        Text(
            text = item.summary,
            color = TextSec,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

private fun formatSummaryWhen(item: CrmNoteSummaryItem): String? {
    val dateTime = parseSummaryDateTime(item) ?: return null
    val day = dayWithOrdinal(dateTime.dayOfMonth)
    val month = dateTime.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val year = (dateTime.year % 100).toString().padStart(2, '0')
    val time = dateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    return "$day $month '$year · $time"
}

private fun parseSummaryDateTime(item: CrmNoteSummaryItem): LocalDateTime? {
    item.datetime?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { OffsetDateTime.parse(raw).toLocalDateTime() }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(raw) }.getOrNull()?.let { return it }
    }

    val date = item.date?.trim().orEmpty()
    if (date.isEmpty()) return null

    return runCatching {
        val parsedDate = LocalDate.parse(date)
        val parsedTime =
            item.time?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) }
                ?: LocalTime.MIDNIGHT
        LocalDateTime.of(parsedDate, parsedTime)
    }.getOrNull()
}

private fun dayWithOrdinal(day: Int): String {
    if (day in 11..13) return "${day}th"
    return when (day % 10) {
        1 -> "${day}st"
        2 -> "${day}nd"
        3 -> "${day}rd"
        else -> "${day}th"
    }
}
