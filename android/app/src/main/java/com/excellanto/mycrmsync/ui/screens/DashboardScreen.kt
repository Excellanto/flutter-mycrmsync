package com.excellanto.mycrmsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.DashboardCallRow
import com.excellanto.mycrmsync.accessibility.WhatsAppAccessibilityHelper
import com.excellanto.mycrmsync.ui.components.ApiSnackbarEffect
import com.excellanto.mycrmsync.ui.components.ApiSnackbarHost
import com.excellanto.mycrmsync.ui.components.rememberApiSnackbarHostState
import com.excellanto.mycrmsync.ui.theme.*
import com.excellanto.mycrmsync.ui.viewmodel.DashboardViewModel
import com.excellanto.mycrmsync.ui.viewmodel.DateRange
import com.excellanto.mycrmsync.ui.viewmodel.StaffStat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChipBlue = Color(0xFFE4ECFF)
private val CardShadow = Color(0x14000000)
private val KpiBlue = Color(0xFF3B82F6)
private val KpiGreen = Color(0xFF10B981)
private val KpiOrange = Color(0xFFF59E0B)
private val KpiRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    onMenuClick: () -> Unit = {},
    onWhatsAppSetupClick: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = rememberApiSnackbarHostState()
    val context = LocalContext.current
    var showWhatsAppSetupPrompt by remember {
        mutableStateOf(WhatsAppAccessibilityHelper.shouldShowSetupPrompt(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    showWhatsAppSetupPrompt = WhatsAppAccessibilityHelper.shouldShowSetupPrompt(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ApiSnackbarEffect(
        message = error,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.consumeError() },
    )

    Scaffold(
        containerColor = LightBg,
        snackbarHost = { ApiSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Dashboard", fontWeight = FontWeight.Bold, color = TextMain) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TextMain)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LightBg)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                if (showWhatsAppSetupPrompt) {
                    WhatsAppSetupBanner(onClick = onWhatsAppSetupClick)
                    Spacer(Modifier.height(12.dp))
                }
                DateRangeChips(selected = ui.range, onSelect = viewModel::setRange)
                Spacer(Modifier.height(16.dp))
                
                StaffSelector(
                    options = ui.staffOptions,
                    selectedId = ui.selectedStaffId,
                    onSelect = viewModel::setStaff,
                )
                Spacer(Modifier.height(10.dp))
                ViewingContext(
                    selectedStaffId = ui.selectedStaffId,
                    staffName = ui.staffOptions.firstOrNull { it.first == ui.selectedStaffId }?.second,
                )
                Spacer(Modifier.height(14.dp))

                when {
                    isLoading && ui.kpis.totalCalls == 0 -> DashboardLoadingBox()
                    else -> {
                        KpiGrid(kpis = ui.kpis)
                        Spacer(Modifier.height(18.dp))
                        DonutCard(kpis = ui.kpis)
                        Spacer(Modifier.height(18.dp))
                        CallsPerDayCard(
                            labels = ui.series.labels,
                            incoming = ui.series.incoming,
                            outgoing = ui.series.outgoing,
                            missed = ui.series.missed,
                        )
                        Spacer(Modifier.height(18.dp))
                        LeaderboardCard(entries = ui.leaderboard)
                        Spacer(Modifier.height(18.dp))
                        RecentCallsCard(rows = ui.recent)
                    }
                }
            }
        }
    }
}

// ----------------- Header controls -----------------

@Composable
private fun ViewingContext(selectedStaffId: String?, staffName: String?) {
    val (label, value, icon, tint) =
        if (selectedStaffId == null) {
            Quadruple("VIEWING", "All users", Icons.Default.Groups, DarkBlue)
        } else {
            Quadruple("VIEWING", staffName ?: "User", Icons.Default.Person, PrimaryBlue)
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardWhite)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextSec, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun DateRangeChips(selected: DateRange, onSelect: (DateRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateRange.values().forEach { r ->
            val isSel = r == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isSel) DarkBlue else Color(0xFFF1F5F9))
                    .clickable { onSelect(r) }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    r.label,
                    color = if (isSel) Color.White else TextSec,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StaffSelector(
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label =
        if (selectedId == null) {
            "All"
        } else {
            options.firstOrNull { it.first == selectedId }?.second ?: "User"
        }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardWhite)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextSec, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All") },
                onClick = { onSelect(null); expanded = false }
            )
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(id); expanded = false }
                )
            }
        }
    }
}

// ----------------- KPI grid -----------------

@Composable
private fun KpiGrid(kpis: com.excellanto.mycrmsync.ui.viewmodel.DashboardKpis) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KpiCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Phone,
                iconTint = KpiBlue,
                label = "TOTAL CALLS",
                value = formatNumberWithCommas(kpis.totalCalls),
                footer = formatPctDelta(kpis.totalDeltaPct),
                footerColor = if (kpis.totalDeltaPct >= 0) KpiGreen else KpiRed,
            )
            KpiCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Timer,
                iconTint = TextMain,
                label = "TALK TIME",
                value = formatTalkTime(kpis.talkTimeSec),
                footer = "Avg ${formatOneDecimal(kpis.avgPerStaffHours)}h/staff",
                footerColor = TextSec,
                footerIsDelta = false,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KpiCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Schedule,
                iconTint = KpiBlue,
                label = "AVG DURATION",
                value = formatDur(kpis.avgDurationSec),
                footer = formatSecondsDelta(kpis.avgDurationDeltaSec),
                footerColor = if (kpis.avgDurationDeltaSec >= 0) KpiGreen else KpiRed,
            )
            KpiCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CallMade,
                iconTint = KpiOrange,
                label = "OUTGOING CALLS",
                value = formatNumberWithCommas(kpis.outgoing),
                footer = formatPctDelta(kpis.outgoingDelta),
                footerColor = if (kpis.outgoingDelta >= 0) KpiGreen else KpiRed,
            )
        }
    }
}

@Composable
private fun KpiCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    valueColor: Color = TextMain,
    footer: String,
    footerColor: Color,
    footerIsDelta: Boolean = true,
) {
    Card(
        modifier = modifier.height(132.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    color = TextMain,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                value,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                footer,
                color = footerColor,
                fontSize = 11.sp,
                fontWeight = if (footerIsDelta) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

private fun formatPctDelta(pct: Int): String {
    if (pct == 0) return "No change vs last period"
    val arrow = if (pct > 0) "↑" else "↓"
    return "$arrow ${kotlin.math.abs(pct)}% vs last period"
}

private fun formatSecondsDelta(sec: Int): String {
    if (sec == 0) return "No change vs last period"
    val arrow = if (sec > 0) "↑" else "↓"
    return "$arrow ${kotlin.math.abs(sec)}s vs last period"
}

private fun formatMissedDelta(pct: Int): String {
    // Fewer missed calls is an improvement.
    if (pct == 0) return "No change vs last period"
    return if (pct < 0) "↑ ${kotlin.math.abs(pct)}% improvement"
    else "↓ $pct% vs last period"
}

private fun formatTalkTime(sec: Long): String {
    if (sec <= 0) return "0m"
    val hours = sec / 3600
    val minutes = (sec % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        else -> "${minutes}m"
    }
}

private fun formatOneDecimal(v: Double): String =
    String.format(Locale.US, "%.1f", v)

private fun formatNumberWithCommas(n: Int): String =
    String.format(Locale.US, "%,d", n)

// ----------------- Donut: Calls by Direction -----------------

@Composable
private fun DonutCard(kpis: com.excellanto.mycrmsync.ui.viewmodel.DashboardKpis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Calls by Direction", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.MoreHoriz, contentDescription = null, tint = TextSec)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    values = listOf(kpis.incoming, kpis.outgoing, kpis.missed),
                    colors = listOf(KpiGreen, KpiBlue, KpiRed),
                    centerTop = kpis.totalCalls.toString(),
                    centerBottom = "Total",
                    modifier = Modifier.size(150.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    LegendRow("Incoming", kpis.incoming, kpis.totalCalls, KpiGreen)
                    LegendRow("Outgoing", kpis.outgoing, kpis.totalCalls, KpiBlue)
                    LegendRow("Missed", kpis.missed, kpis.totalCalls, KpiRed)
                }
            }
        }
    }
}

@Composable
private fun DonutChart(
    values: List<Int>,
    colors: List<Color>,
    centerTop: String,
    centerBottom: String,
    modifier: Modifier = Modifier,
) {
    val total = values.sum().coerceAtLeast(1)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val pad = stroke / 2
            val sz = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(pad, pad)
            // Track
            drawArc(
                color = Color(0xFFF1F5F9),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = sz,
                style = Stroke(width = stroke),
            )
            var start = -90f
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val sweep = (v.toFloat() / total) * 360f
                drawArc(
                    color = colors[i],
                    startAngle = start + 1f,
                    sweepAngle = (sweep - 2f).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = sz,
                    style = Stroke(width = stroke),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text(centerBottom, color = TextSec, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LegendRow(label: String, value: Int, total: Int, color: Color) {
    val pct = if (total == 0) 0 else value * 100 / total
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("$value calls", color = TextSec, fontSize = 11.sp)
        }
        Text("$pct%", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ----------------- Bar chart: Calls per Day -----------------

@Composable
private fun CallsPerDayCard(
    labels: List<String>,
    incoming: List<Int>,
    outgoing: List<Int>,
    missed: List<Int>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Calls per Day", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(KpiGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(KpiGreen)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", color = KpiGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            StackedBarChart(
                labels = labels,
                incoming = incoming,
                outgoing = outgoing,
                missed = missed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot("Incoming", KpiGreen)
                LegendDot("Outgoing", KpiBlue)
                LegendDot("Missed", KpiRed)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = TextSec, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StackedBarChart(
    labels: List<String>,
    incoming: List<Int>,
    outgoing: List<Int>,
    missed: List<Int>,
    modifier: Modifier = Modifier,
) {
    val n = labels.size.coerceAtLeast(1)
    val totals = (0 until n).map {
        (incoming.getOrElse(it) { 0 }) + (outgoing.getOrElse(it) { 0 }) + (missed.getOrElse(it) { 0 })
    }
    val max = (totals.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width
            val h = size.height
            val slot = w / n
            val barWidth = (slot * 0.55f).coerceAtLeast(6f)
            val corner = 6f

            // gridlines (3)
            val grid = Color(0xFFE2E8F0)
            for (i in 1..3) {
                val y = h * (i / 4f)
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            for (i in 0 until n) {
                val cx = slot * i + slot / 2f
                val inV = incoming.getOrElse(i) { 0 }
                val outV = outgoing.getOrElse(i) { 0 }
                val mV = missed.getOrElse(i) { 0 }
                val inH = (inV.toFloat() / max) * (h - 8f)
                val outH = (outV.toFloat() / max) * (h - 8f)
                val mH = (mV.toFloat() / max) * (h - 8f)

                var yCursor = h
                // Incoming (bottom)
                if (inH > 0f) {
                    drawRoundRect(
                        color = KpiGreen,
                        topLeft = Offset(cx - barWidth / 2, yCursor - inH),
                        size = Size(barWidth, inH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                    )
                    yCursor -= inH
                }
                if (outH > 0f) {
                    drawRoundRect(
                        color = KpiBlue,
                        topLeft = Offset(cx - barWidth / 2, yCursor - outH),
                        size = Size(barWidth, outH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                    )
                    yCursor -= outH
                }
                if (mH > 0f) {
                    drawRoundRect(
                        color = KpiRed,
                        topLeft = Offset(cx - barWidth / 2, yCursor - mH),
                        size = Size(barWidth, mH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { l ->
                Text(
                    l,
                    color = TextSec,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ----------------- Leaderboard -----------------

@Composable
private fun LeaderboardCard(entries: List<StaffStat>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Top Staff Leaderboard", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("View All", color = PrimaryBlue, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) {
                Text("No staff data yet", color = TextSec, fontSize = 12.sp)
            } else {
                entries.forEachIndexed { idx, s ->
                    LeaderboardRow(rank = idx + 1, stat = s)
                    if (idx != entries.lastIndex) {
                        Divider(color = Color(0xFFF1F5F9), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, stat: StaffStat) {
    val rankColor = when (rank) {
        1 -> KpiOrange
        2 -> Color(0xFF94A3B8)
        3 -> Color(0xFFB07B5C)
        else -> TextSec
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(PrimaryBlue.copy(alpha = 0.12f))
                .border(2.dp, rankColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(stat.initials, color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", color = rankColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(stat.name, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(
                "${stat.totalCalls} calls · ${formatDur(stat.talkTimeSec)} talk",
                color = TextSec,
                fontSize = 11.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${stat.connectRatePct}%", color = KpiGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("connect", color = TextSec, fontSize = 10.sp)
        }
    }
}

// ----------------- Recent calls -----------------

@Composable
private fun WhatsAppSetupBanner(onClick: () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F9EE)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_whatsapp),
                contentDescription = null,
                tint = Color(0xFF25D366),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Enable WhatsApp call tracking", fontWeight = FontWeight.SemiBold, color = TextMain, fontSize = 13.sp)
                Text("Tap to turn on accessibility for automatic call logging", color = TextSec, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RecentCallsCard(
    rows: List<DashboardCallRow>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Calls", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("No calls in this range", color = TextSec, fontSize = 12.sp)
            } else {
                rows.forEachIndexed { idx, row ->
                    RecentCallRow(row)
                    if (idx != rows.lastIndex) {
                        Divider(color = Color(0xFFF1F5F9), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCallRow(row: DashboardCallRow) {
    val dir = row.direction.uppercase(Locale.getDefault())
    val (icon, color) = when (dir) {
        "INCOMING" -> Icons.Default.CallReceived to KpiGreen
        "OUTGOING" -> Icons.Default.CallMade to KpiBlue
        "MISSED" -> Icons.Default.CallMissed to KpiRed
        else -> Icons.Default.Phone to TextSec
    }
    val name = row.contactName?.takeIf { it.isNotBlank() } ?: (row.phoneE164 ?: row.phoneRaw ?: "Unknown")
    val timeShort = formatRelative(row.startedAt)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (row.isWhatsAppCall) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_whatsapp),
                        contentDescription = "WhatsApp",
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(row.phoneE164 ?: row.phoneRaw ?: "", color = TextSec, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(timeShort, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            if (dir == "MISSED") {
                Text("Missed", color = KpiRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(formatDur(row.durationSec ?: 0), color = PrimaryBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ----------------- Small helpers -----------------

@Composable
private fun DashboardLoadingBox() {
    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PrimaryBlue)
    }
}

private fun formatDur(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return if (m <= 0) "${s}s" else "${m}m ${s.toString().padStart(2, '0')}s"
}

private fun formatRelative(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        val odt = OffsetDateTime.parse(iso)
        val zdt = odt.atZoneSameInstant(ZoneId.systemDefault())
        zdt.format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US))
    }.getOrElse { "—" }
}
