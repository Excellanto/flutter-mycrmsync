package com.excellanto.mycrmsync.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.excellanto.mycrmsync.data.CallLogRegistrationEvents
import com.excellanto.mycrmsync.data.CallLogRegistrationSync
import com.excellanto.mycrmsync.data.WhatsAppCallRegistrationSync
import com.excellanto.mycrmsync.data.CallDirection
import com.excellanto.mycrmsync.data.CrmUsersLoader
import com.excellanto.mycrmsync.data.DashboardCallRow
import com.excellanto.mycrmsync.data.DeviceCallLogDashboardLoader
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DateRange(val days: Long, val label: String) {
    TODAY(1, "Today"),
    LAST_7(7, "7D"),
    LAST_30(30, "30D"),
    CUSTOM(0, "Custom"),
}

data class DashboardKpis(
    val totalCalls: Int = 0,
    val incoming: Int = 0,
    val outgoing: Int = 0,
    val missed: Int = 0,
    val avgDurationSec: Int = 0,
    val todayTotal: Int = 0,
    val totalDeltaPct: Int = 0,
    val incomingDelta: Int = 0,
    val outgoingDelta: Int = 0,
    val missedDeltaPct: Int = 0,
    val talkTimeSec: Long = 0,
    val avgPerStaffHours: Double = 0.0,
    val avgDurationDeltaSec: Int = 0,
)

data class StaffStat(
    val userId: String,
    val name: String,
    val initials: String,
    val totalCalls: Int,
    val avgDurationSec: Int,
    val talkTimeSec: Int,
    val connectRatePct: Int,
)

data class DaySeries(
    val labels: List<String>,
    val incoming: List<Int>,
    val outgoing: List<Int>,
    val missed: List<Int>,
)

data class DashboardUiState(
    val range: DateRange = DateRange.TODAY,
    val selectedStaffId: String? = null,
    val kpis: DashboardKpis = DashboardKpis(),
    val series: DaySeries = DaySeries(emptyList(), emptyList(), emptyList(), emptyList()),
    val leaderboard: List<StaffStat> = emptyList(),
    val recent: List<DashboardCallRow> = emptyList(),
    val staffOptions: List<Pair<String, String>> = emptyList(), // id -> name
    val isAdmin: Boolean = false,
    val currentUserId: String? = null,
    val currentUserName: String? = null,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = PrefsManager(app)
    private val crmApi = CrmApiService.create()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun consumeError() {
        _error.value = null
    }

    private val _ui = MutableStateFlow(
        DashboardUiState(
            isAdmin = prefs.isAdmin(),
            currentUserId = prefs.getUserId(),
            currentUserName = prefs.getUserName(),
            selectedStaffId = null,
        )
    )
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    private var allRows: List<DashboardCallRow> = emptyList()
    private val refreshMutex = Mutex()

    init {
        refresh()
        viewModelScope.launch {
            CallLogRegistrationEvents.registrations.collect {
                refresh(showBlockingLoader = false, skipRegistrationSync = true)
            }
        }
    }

    fun setRange(range: DateRange) {
        _ui.value = _ui.value.copy(range = range)
        recompute()
    }

    fun setStaff(id: String?) {
        _ui.value = _ui.value.copy(selectedStaffId = id)
        recompute()
    }

    fun refresh(showBlockingLoader: Boolean = true, skipRegistrationSync: Boolean = false) {
        viewModelScope.launch {
            refreshMutex.withLock {
                if (showBlockingLoader) {
                    _isLoading.value = true
                }
                _error.value = null
                try {
                val admin = prefs.isAdmin()
                val currentUserId = prefs.getUserId()
                val currentUserName = prefs.getUserName()
                _ui.value = _ui.value.copy(
                    isAdmin = admin,
                    currentUserId = currentUserId,
                    currentUserName = currentUserName,
                )

                val (authorization, crmUserId) = prefs.requireCrmSession()
                val meUserId =
                    prefs.getRegisterUserId()?.toString()
                        ?: crmUserId

                if (!skipRegistrationSync) {
                    withContext(Dispatchers.IO) {
                        runCatching { CallLogRegistrationSync(getApplication()).sync() }
                        runCatching { WhatsAppCallRegistrationSync(getApplication()).sync() }
                    }
                    CallLogSyncScheduler.enqueueNow(getApplication())
                }

                val loaded =
                    withContext(Dispatchers.IO) {
                        DeviceCallLogDashboardLoader.loadFromApi(
                            api = crmApi,
                            authorization = authorization,
                            meUserId = meUserId,
                            currentUserId = currentUserId,
                            currentUserName = currentUserName,
                            db = CrmDatabase.getInstance(getApplication()),
                        )
                    }

                val staffOptions =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            CrmUsersLoader.fetchStaffOptions(
                                api = crmApi,
                                authorization = authorization,
                                userId = crmUserId,
                            )
                        }.getOrDefault(emptyList())
                    }.ifEmpty { loaded.staffOptions }
                        .ifEmpty {
                            buildList {
                                val id = meUserId.trim().ifBlank { null } ?: return@buildList
                                val name = currentUserName?.trim()?.ifBlank { null } ?: "Me"
                                add(id to name)
                            }
                        }

                allRows = loaded.rows
                _ui.value = _ui.value.copy(staffOptions = staffOptions)
                recompute()
                } catch (e: Exception) {
                    _error.value = e.localizedMessage ?: "Failed to load dashboard"
                } finally {
                    if (showBlockingLoader) {
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    private fun recompute() {
        val state = _ui.value
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val (startDate, endDate) = when (state.range) {
            DateRange.TODAY -> today to today
            DateRange.LAST_7 -> today.minusDays(6) to today
            DateRange.LAST_30 -> today.minusDays(29) to today
            DateRange.CUSTOM -> today.minusDays(6) to today
        }

        val scoped = allRows.filter { row ->
            val dt = parseStarted(row) ?: return@filter false
            val d = dt.atZoneSameInstant(zone).toLocalDate()
            val inRange = !d.isBefore(startDate) && !d.isAfter(endDate)
            inRange && rowMatchesScope(row, state)
        }

        val incoming = scoped.count { CallDirection.isIncoming(it.direction) }
        val outgoing = scoped.count { CallDirection.isOutgoing(it.direction) }
        val missed = scoped.count { CallDirection.isMissed(it.direction) }
        val total = scoped.size
        val connected = scoped.filter { CallDirection.isConnected(it.direction) }
        val avgDur = if (connected.isNotEmpty()) {
            connected.sumOf { it.durationSec ?: 0 } / connected.size
        } else 0
        val todayTotal = scoped.count { row ->
            val d = parseStarted(row)?.atZoneSameInstant(zone)?.toLocalDate()
            d == today
        }
        val talkTime = connected.sumOf { (it.durationSec ?: 0).toLong() }
        val staffCount = scoped.mapNotNull { it.userId?.takeIf { u -> u.isNotBlank() } }
            .toSet().size.coerceAtLeast(1)
        val avgPerStaffHours = (talkTime.toDouble() / 3600.0) / staffCount.toDouble()

        val periodDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1
        val prevStart = startDate.minusDays(periodDays)
        val prevEnd = startDate.minusDays(1)
        val prev = allRows.filter { row ->
            val dt = parseStarted(row) ?: return@filter false
            val d = dt.atZoneSameInstant(zone).toLocalDate()
            rowMatchesScope(row, state) && !d.isBefore(prevStart) && !d.isAfter(prevEnd)
        }
        val prevIn = prev.count { CallDirection.isIncoming(it.direction) }
        val prevOut = prev.count { CallDirection.isOutgoing(it.direction) }
        val prevMiss = prev.count { CallDirection.isMissed(it.direction) }
        val prevConnected = prev.filter { CallDirection.isConnected(it.direction) }
        val prevAvgDur = if (prevConnected.isNotEmpty()) {
            prevConnected.sumOf { it.durationSec ?: 0 } / prevConnected.size
        } else 0

        val kpis = DashboardKpis(
            totalCalls = total,
            incoming = incoming,
            outgoing = outgoing,
            missed = missed,
            avgDurationSec = avgDur,
            todayTotal = todayTotal,
            totalDeltaPct = pctDelta(total, prev.size),
            incomingDelta = pctDelta(incoming, prevIn),
            outgoingDelta = pctDelta(outgoing, prevOut),
            missedDeltaPct = pctDelta(missed, prevMiss),
            talkTimeSec = talkTime,
            avgPerStaffHours = avgPerStaffHours,
            avgDurationDeltaSec = avgDur - prevAvgDur,
        )

        val bucketCount = when (state.range) {
            DateRange.TODAY -> 1
            DateRange.LAST_7 -> 7
            DateRange.LAST_30 -> 30
            DateRange.CUSTOM -> periodDays.toInt().coerceIn(1, 30)
        }
        val series = buildSeries(scoped, startDate, endDate, bucketCount, zone)

        val userName = state.staffOptions.associate { it.first to it.second }
        val leaderboard = scoped
            .filter { !it.userId.isNullOrBlank() }
            .groupBy { it.userId!! }
            .map { (uid, rows) ->
                val name = userName[uid] ?: "Unknown"
                val totalU = rows.size
                val connU = rows.filter { CallDirection.isConnected(it.direction) }
                val avg = if (connU.isNotEmpty()) connU.sumOf { it.durationSec ?: 0 } / connU.size else 0
                val talk = connU.sumOf { it.durationSec ?: 0 }
                val rate = if (totalU > 0) (connU.size * 100 / totalU) else 0
                StaffStat(
                    userId = uid,
                    name = name,
                    initials = initialsOf(name),
                    totalCalls = totalU,
                    avgDurationSec = avg,
                    talkTimeSec = talk,
                    connectRatePct = rate,
                )
            }
            .sortedByDescending { it.totalCalls }
            .take(5)

        val recent = scoped.sortedByDescending { parseStarted(it)?.toEpochSecond() ?: 0 }.take(5)

        _ui.value = state.copy(
            kpis = kpis,
            series = series,
            leaderboard = leaderboard,
            recent = recent,
        )
    }

    private fun buildSeries(
        rows: List<DashboardCallRow>,
        startDate: LocalDate,
        endDate: LocalDate,
        bucketCount: Int,
        zone: ZoneId,
    ): DaySeries {
        val days = (0 until bucketCount).map { startDate.plusDays(it.toLong()) }
        val labels = if (bucketCount == 7) {
            days.map { shortDow(it.dayOfWeek) }
        } else if (bucketCount == 1) {
            listOf("Today")
        } else {
            days.map { d -> d.dayOfMonth.toString() }
        }

        val incoming = IntArray(bucketCount)
        val outgoing = IntArray(bucketCount)
        val missed = IntArray(bucketCount)

        rows.forEach { row ->
            val dt = parseStarted(row) ?: return@forEach
            val d = dt.atZoneSameInstant(zone).toLocalDate()
            val idx = java.time.temporal.ChronoUnit.DAYS.between(startDate, d).toInt()
            if (idx in 0 until bucketCount) {
                when (CallDirection.normalize(row.direction)) {
                    "INCOMING" -> incoming[idx]++
                    "OUTGOING" -> outgoing[idx]++
                    "MISSED" -> missed[idx]++
                }
            }
        }
        return DaySeries(labels, incoming.toList(), outgoing.toList(), missed.toList())
    }

    private fun rowMatchesScope(row: DashboardCallRow, state: DashboardUiState): Boolean {
        val selected = state.selectedStaffId?.trim()?.ifBlank { null } ?: return true
        val rowUserId = row.userId?.trim()?.ifBlank { null } ?: return false
        return rowUserId == selected
    }

    private fun parseStarted(row: DashboardCallRow): OffsetDateTime? {
        val s = row.startedAt ?: return null
        parseStartedIso(s)?.let { return it }
        return null
    }

    private fun parseStartedIso(raw: String): OffsetDateTime? {
        runCatching { return OffsetDateTime.parse(raw) }
        runCatching { return Instant.parse(raw).atOffset(java.time.ZoneOffset.UTC) }
        runCatching {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atOffset(java.time.ZoneOffset.UTC)
        }
        return null
    }

    private fun pctDelta(current: Int, previous: Int): Int {
        if (previous == 0) return if (current == 0) 0 else 100
        return ((current - previous) * 100.0 / previous).toInt()
    }

    private fun shortDow(dow: DayOfWeek): String = when (dow) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }

    private fun initialsOf(name: String): String {
        if (name.isBlank()) return "?"
        val parts = name.trim().split(Regex("\\s+"))
        return when {
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }
}
