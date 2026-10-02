package com.excellanto.mycrmsync.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.ui.components.AiEnhanceTranscriptionDialog
import com.excellanto.mycrmsync.ui.components.ApiSnackbarEffect
import com.excellanto.mycrmsync.ui.components.ApiSnackbarHost
import com.excellanto.mycrmsync.ui.components.AppLoadingBox
import com.excellanto.mycrmsync.ui.components.rememberApiSnackbarHostState
import com.excellanto.mycrmsync.ui.components.RecordingPlayerBottomSheet
import com.excellanto.mycrmsync.ui.components.RecordingQueueItem
import com.excellanto.mycrmsync.ui.theme.CardWhite
import com.excellanto.mycrmsync.ui.theme.ErrorRed
import com.excellanto.mycrmsync.ui.theme.LightBg
import com.excellanto.mycrmsync.ui.theme.PrimaryBlue
import com.excellanto.mycrmsync.ui.theme.SuccessGreen
import com.excellanto.mycrmsync.ui.theme.TextMain
import com.excellanto.mycrmsync.ui.theme.TextSec
import com.excellanto.mycrmsync.data.CallLogListScope
import com.excellanto.mycrmsync.data.CallDirection
import com.excellanto.mycrmsync.ui.viewmodel.CallLogsViewModel
import com.excellanto.mycrmsync.ui.viewmodel.UiCallLogRow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val ActionButtonBg = Color(0xFFE8EFFF)
private val ActionButtonIcon = Color(0xFF3B66D1)
private val CardBorder = Color(0xFFE5E7EB)
private val MissedNameRed = Color(0xFFA62D2D)

private sealed interface CallLogListEntry {
    data class Header(val title: String) : CallLogListEntry
    data class Entry(val row: UiCallLogRow) : CallLogListEntry
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun CallLogsScreen(
    viewModel: CallLogsViewModel = viewModel(),
    onMenuClick: () -> Unit = {},
) {
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val snackbarHostState = rememberApiSnackbarHostState()

    ApiSnackbarEffect(
        message = snackbarMessage,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.consumeSnackbarMessage() },
    )
    ApiSnackbarEffect(
        message = error,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.consumeError() },
    )

    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val listScope by viewModel.scope.collectAsStateWithLifecycle()
    val aiEnhanceState by viewModel.aiEnhanceState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    var search by remember { mutableStateOf("") }

    var playerSession by remember { mutableStateOf<PlayerSession?>(null) }

    val filtered = remember(rows, search) {
        if (search.isBlank()) rows
        else {
            val q = search.trim().lowercase(Locale.getDefault())
            rows.filter {
                it.contactDisplayName.lowercase(Locale.getDefault()).contains(q) ||
                    (it.phoneE164 ?: it.phoneRaw ?: "").lowercase(Locale.getDefault()).contains(q)
            }
        }
    }

    val grouped = remember(filtered) { buildGroupedEntries(filtered) }

    val pendingUploadFiltered =
        remember(filtered) { filtered.filterNot { it.crmCallStatusNoteUploaded } }

    AiEnhanceTranscriptionDialog(
        state = aiEnhanceState,
        onDismissRequest = { viewModel.dismissAiEnhance() },
        onUpdateToNotes = { viewModel.postAiEnhancementToCrm() },
    )

    playerSession?.let { session ->
        RecordingPlayerBottomSheet(
            queue = session.queue,
            startIndex = session.startIndex,
            onDismiss = { playerSession = null },
            onDeleteRecording = { androidId, done ->
                viewModel.deleteLocalRecording(androidId, done)
            },
        )
    }

    val pullRefreshState =
        rememberPullRefreshState(isRefreshing, { viewModel.refresh(showBlockingLoader = false) })
    Scaffold(
        containerColor = LightBg,
        snackbarHost = { ApiSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Call Logs", fontWeight = FontWeight.Bold, color = TextMain) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TextMain)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.uploadNotesForFilteredRows(pendingUploadFiltered) },
                        enabled = pendingUploadFiltered.isNotEmpty(),
                    ) {
                        Text("Upload all", color = PrimaryBlue, fontSize = 12.sp)
                    }
                    IconButton(onClick = { viewModel.refresh(showBlockingLoader = false) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextMain)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LightBg),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            TextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardWhite),
                placeholder = { Text("Search name or number...", color = TextSec, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSec) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CardWhite,
                    unfocusedContainerColor = CardWhite,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            Spacer(Modifier.height(12.dp))

            CallLogScopeSelector(
                selected = listScope,
                onSelect = viewModel::setScope,
            )

            Spacer(Modifier.height(12.dp))

            when {
                isLoading -> AppLoadingBox()
                filtered.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pullRefresh(pullRefreshState),
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PhoneDisabled, contentDescription = null, tint = TextSec)
                                Spacer(Modifier.height(8.dp))
                                Text("No calls found", color = TextMain, fontWeight = FontWeight.Bold)
                                Text("Try adjusting your search or pull to refresh.", color = TextSec, fontSize = 12.sp)
                            }
                        }
                        PullRefreshIndicator(
                            refreshing = isRefreshing,
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pullRefresh(pullRefreshState),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            itemsIndexed(
                                items = grouped,
                                key = { index, e ->
                                    when (e) {
                                        is CallLogListEntry.Header -> "h:$index-${e.title}"
                                        is CallLogListEntry.Entry -> e.row.listItemKey()
                                    }
                                }
                            ) { _, entry ->
                                when (entry) {
                                    is CallLogListEntry.Header -> {
                                        Text(
                                            entry.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp, horizontal = 2.dp),
                                            color = TextSec,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    is CallLogListEntry.Entry -> {
                                        CallLogRowCard(
                                            row = entry.row,
                                            showStaffName = listScope == CallLogListScope.ALL,
                                            onUploadThisCall = { viewModel.uploadNoteForSingleRow(entry.row) },
                                            onUploadToAi = {
                                                viewModel.uploadRecordingToAi(entry.row)
                                            },
                                            onPlayRecording = { r ->
                                                val q = buildRecordingQueue(filtered)
                                                val rowKey = r.listItemKey()
                                                val idx =
                                                    filtered
                                                        .filter { it.recordingUri?.isNotBlank() == true }
                                                        .indexOfFirst { it.listItemKey() == rowKey }
                                                if (idx >= 0) {
                                                    playerSession = PlayerSession(queue = q, startIndex = idx)
                                                }
                                            },
                                        )
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                        PullRefreshIndicator(
                            refreshing = isRefreshing,
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }
}

private data class PlayerSession(
    val queue: List<RecordingQueueItem>,
    val startIndex: Int,
)

private fun buildRecordingQueue(rows: List<UiCallLogRow>): List<RecordingQueueItem> {
    return rows.mapNotNull { r ->
        val uriStr = r.recordingUri?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@mapNotNull null
        RecordingQueueItem(
            uri = uri,
            contactName = r.contactDisplayName,
            phone = r.phoneE164 ?: r.phoneRaw ?: "",
            recordedAtIso = r.startedAtIso,
            androidCallLogId = r.androidCallLogId,
        )
    }
}

@Composable
private fun CallLogRowCard(
    row: UiCallLogRow,
    showStaffName: Boolean = false,
    onUploadThisCall: () -> Unit,
    onUploadToAi: () -> Unit,
    onPlayRecording: (UiCallLogRow) -> Unit,
) {
    val context = LocalContext.current
    val direction = CallDirection.normalize(row.direction)
    val name = row.contactDisplayName
    val number = row.phoneE164 ?: row.phoneRaw ?: ""

    val timeInfo = remember(row.startedAtIso) { formatStartedAt(row.startedAtIso) }
    val durationLabel = formatDurationShort(row.durationSec, direction)
    val directionLbl = directionLabel(direction)

    val directionChipText =
        when (direction) {
            "INCOMING" -> "INCOMING"
            "OUTGOING" -> "OUTGOING"
            "MISSED" -> "MISSED"
            else -> "UNKNOWN"
        }

    val (icon, accent, bg) =
        when (direction) {
            "INCOMING" -> Triple(Icons.Default.CallReceived, SuccessGreen, SuccessGreen.copy(alpha = 0.12f))
            "OUTGOING" -> Triple(Icons.Default.CallMade, PrimaryBlue, PrimaryBlue.copy(alpha = 0.12f))
            "MISSED" -> Triple(Icons.Default.CallMissed, ErrorRed, ErrorRed.copy(alpha = 0.10f))
            else -> Triple(Icons.Default.HelpOutline, TextSec, TextSec.copy(alpha = 0.12f))
        }

    val isMissed = direction == "MISSED"
    val oemUri = row.recordingUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    val hasRecording = oemUri != null

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = directionLbl,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        color = if (isMissed) MissedNameRed else TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (number.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            number,
                            color = TextSec,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (row.isWhatsAppCall) {
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_whatsapp),
                                contentDescription = "WhatsApp",
                                tint = Color(0xFF25D366),
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "WhatsApp",
                                color = Color(0xFF25D366),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (row.whatsAppSyncPending) {
                                Spacer(Modifier.width(6.dp))
                                Text("· Pending sync", color = TextSec, fontSize = 10.sp)
                            }
                        }
                    }

                    if (showStaffName && !row.staffUserName.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            row.staffUserName,
                            color = PrimaryBlue,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CallLogActionButton(
                            icon = Icons.Default.Call,
                            contentDescription = "Call",
                            onClick = {
                                val raw = row.phoneE164 ?: row.phoneRaw
                                if (!raw.isNullOrBlank()) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_DIAL,
                                                Uri.parse("tel:${Uri.encode(raw)}"),
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                        if (hasRecording) {
                            CallLogActionButton(
                                icon = Icons.Default.PlayArrow,
                                contentDescription = "Play recording",
                                onClick = { onPlayRecording(row) },
                            )
                        }
                    }

                    if (durationLabel != null) {
                        Text(
                            durationLabel,
                            color = TextMain,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(bg)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        directionChipText,
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = TextSec,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    timeInfo.time,
                    color = TextSec,
                    fontSize = 10.sp,
                    maxLines = 1,
                )

                Spacer(Modifier.width(8.dp))

                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = TextSec,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    timeInfo.date,
                    color = TextSec,
                    fontSize = 10.sp,
                    maxLines = 1,
                )

                Spacer(Modifier.weight(1f))

                Icon(
                    Icons.Outlined.Cloud,
                    contentDescription = "Add call note",
                    tint = TextSec,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clickable(onClick = onUploadThisCall),
                )
            }

            if (hasRecording) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onUploadToAi,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue,
                            contentColor = Color.White,
                        ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Upload to AI",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallLogActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val modifier =
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(ActionButtonBg)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = ActionButtonIcon,
            modifier = Modifier.size(18.dp),
        )
    }
}

private data class TimeParts(val time: String, val date: String)

private fun formatStartedAt(startedAtIso: String?): TimeParts {
    if (startedAtIso.isNullOrBlank()) return TimeParts("—", "—")
    return runCatching {
        val zdt = OffsetDateTime.parse(startedAtIso).atZoneSameInstant(ZoneId.systemDefault())
        val time = zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        val date = zdt.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        TimeParts(time, date)
    }.getOrElse { TimeParts("—", "—") }
}

private fun formatDurationShort(durationSec: Int?, direction: String): String? {
    if (direction == "MISSED") return null
    val s = durationSec ?: return null
    if (s <= 0) return null
    val m = s / 60
    val r = s % 60
    return when {
        m > 0 && r > 0 -> "${m}m ${r}s"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

private fun directionLabel(raw: String): String {
    return when (raw.uppercase(Locale.getDefault())) {
        "INCOMING", "IN" -> "Incoming"
        "OUTGOING", "OUT" -> "Outgoing"
        "MISSED" -> "Missed"
        else -> raw.ifBlank { "Unknown" }
    }
}

@Composable
private fun CallLogScopeSelector(
    selected: CallLogListScope,
    onSelect: (CallLogListScope) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Get log for",
            color = TextSec,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        FilterChip(
            selected = selected == CallLogListScope.ME,
            onClick = { onSelect(CallLogListScope.ME) },
            label = { Text("Me") },
            colors =
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryBlue.copy(alpha = 0.12f),
                    selectedLabelColor = PrimaryBlue,
                ),
        )
        FilterChip(
            selected = selected == CallLogListScope.ALL,
            onClick = { onSelect(CallLogListScope.ALL) },
            label = { Text("All users") },
            colors =
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryBlue.copy(alpha = 0.12f),
                    selectedLabelColor = PrimaryBlue,
                ),
        )
    }
}

private fun buildGroupedEntries(rows: List<UiCallLogRow>): List<CallLogListEntry> {
    val out = mutableListOf<CallLogListEntry>()
    var prev: String? = null
    for (r in rows) {
        val g = dateGroupLabel(r.startedAtIso)
        if (g != prev) {
            out.add(CallLogListEntry.Header(g))
            prev = g
        }
        out.add(CallLogListEntry.Entry(r))
    }
    return out
}

private fun dateGroupLabel(startedAtIso: String?): String {
    if (startedAtIso.isNullOrBlank()) return "Unknown date"
    return runCatching {
        val zdt = OffsetDateTime.parse(startedAtIso).atZoneSameInstant(ZoneId.systemDefault())
        val date = zdt.toLocalDate()
        val today = LocalDate.now()
        when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            ChronoUnit.DAYS.between(date, today) < 7 ->
                zdt.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
            date.year == today.year ->
                zdt.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
            else ->
                zdt.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        }
    }.getOrElse { "Unknown date" }
}
