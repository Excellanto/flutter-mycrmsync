import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/data/api_client.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:mycrmsync/ui/widgets/app_shell.dart';

enum _DateRange { today, last7, last30 }

class DashboardScreen extends ConsumerStatefulWidget {
  const DashboardScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  ConsumerState<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends ConsumerState<DashboardScreen> {
  static const _kpiBlue = Color(0xFF3B82F6);
  static const _kpiGreen = Color(0xFF10B981);
  static const _kpiOrange = Color(0xFFF59E0B);
  static const _kpiRed = Color(0xFFEF4444);

  bool _loading = true;
  String? _error;
  Map<String, dynamic> _pending = const {};
  int _openTasks = 0;
  int _inProgressTasks = 0;
  int _completedTasks = 0;
  List<_DashboardTask> _todayTasks = const [];

  _DateRange _range = _DateRange.last7;
  String? _selectedStaffId; // null = All users
  List<_CallRow> _allRows = [];
  List<_StaffOption> _staffOptions = [];
  bool _isAdmin = false;
  String? _currentUserId;
  String? _currentUserName;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      promptOverlayIfNeeded(context);
      _load();
    });
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final session = ref.read(sessionProvider).valueOrNull;
      _isAdmin = session?.isAdmin ?? false;
      _currentUserId = session?.effectiveUserId;
      _currentUserName = session?.effectiveUserName;

      if (AppConfig.isAndroid) {
        await NativeBridges.enqueueNow();
        _pending = await NativeBridges.getPendingCounts();
      }

      final api = ref.read(crmApiProvider);
      final logs = await api.getCallLogs(mine: false, limit: 500);
      final users = await api.getUsers();
      final taskSummary = await _loadTaskSummary(api);

      final rows = logs.map(_CallRow.fromApi).whereType<_CallRow>().toList();
      final fromUsers = <_StaffOption>[];
      for (final u in users) {
        final id = '${u['id'] ?? u['user_id'] ?? u['userId'] ?? ''}'.trim();
        if (id.isEmpty) continue;
        final name =
            '${u['name'] ?? u['full_name'] ?? u['fullName'] ?? 'User'}'.trim();
        fromUsers.add(_StaffOption(id: id, name: name.isEmpty ? 'User' : name));
      }

      final fromLogs = <String, String>{};
      for (final r in rows) {
        if (r.userId == null || r.userId!.isEmpty) continue;
        fromLogs[r.userId!] = r.userName?.isNotEmpty == true
            ? r.userName!
            : (fromLogs[r.userId!] ?? 'Unknown');
      }

      var staff = fromUsers.isNotEmpty
          ? fromUsers
          : fromLogs.entries
              .map((e) => _StaffOption(id: e.key, name: e.value))
              .toList();
      if (staff.isEmpty &&
          _currentUserId != null &&
          _currentUserId!.isNotEmpty) {
        staff = [
          _StaffOption(
            id: _currentUserId!,
            name: _currentUserName?.trim().isNotEmpty == true
                ? _currentUserName!
                : 'Me',
          ),
        ];
      }
      staff.sort(
        (a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()),
      );

      if (!mounted) return;
      setState(() {
        _allRows = rows;
        _staffOptions = staff;
        _openTasks = taskSummary.open;
        _inProgressTasks = taskSummary.inProgress;
        _completedTasks = taskSummary.completed;
        _todayTasks = taskSummary.today;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<({int open, int inProgress, int completed, List<_DashboardTask> today})>
      _loadTaskSummary(CrmApi api) async {
    try {
      final tasks = await api.getTasks(status: 'all', kind: 'all');
      var open = 0;
      var inProgress = 0;
      var completed = 0;
      final today = <_DashboardTask>[];
      final now = DateTime.now();
      for (final raw in tasks) {
        final task = _DashboardTask.fromMap(raw);
        if (!task.isReminder) {
          switch (task.status) {
            case 'open':
              open++;
            case 'in_progress':
            case 'in progress':
              inProgress++;
            case 'completed':
              completed++;
          }
        }
        final due = task.dueAt?.toLocal();
        if (due == null || task.status == 'cancelled') continue;
        if (due.year == now.year && due.month == now.month && due.day == now.day) {
          today.add(task);
        }
      }
      today.sort((a, b) => (a.dueAt ?? DateTime(0)).compareTo(b.dueAt ?? DateTime(0)));
      return (open: open, inProgress: inProgress, completed: completed, today: today);
    } catch (_) {
      return (
        open: _openTasks,
        inProgress: _inProgressTasks,
        completed: _completedTasks,
        today: _todayTasks,
      );
    }
  }

  _DashboardComputed get _computed => _DashboardComputed.from(
        rows: _allRows,
        range: _range,
        selectedStaffId: _selectedStaffId,
        staffOptions: _staffOptions,
      );

  @override
  Widget build(BuildContext context) {
    final c = _computed;
    final showStaffPicker = _isAdmin || _staffOptions.length > 1;

    return AppShell(
      title: 'Dashboard',
      currentRoute: 'dashboard',
      onNavigate: widget.onNavigate,
      child: RefreshIndicator(
        onRefresh: _load,
        child: _loading && c.kpis.totalCalls == 0
            ? ListView(
                children: const [
                  SizedBox(height: 180),
                  Center(child: CircularProgressIndicator()),
                ],
              )
            : ListView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
                children: [
                  const Text(
                    'Tasks',
                    style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
                  ),
                  const SizedBox(height: 10),
                  _TaskStatusCounts(
                    open: _openTasks,
                    inProgress: _inProgressTasks,
                    completed: _completedTasks,
                  ),
                  const SizedBox(height: 12),
                  if (_todayTasks.isEmpty)
                    const Text(
                      'No tasks due today',
                      style: TextStyle(color: AppColors.textSec, fontSize: 13),
                    )
                  else
                    _TodayTaskCards(
                      tasks: _todayTasks,
                      onOpen: () => widget.onNavigate('tasks'),
                    ),
                  if (ref.watch(selectedDashboardTaskProvider) case final task?) ...[
                    const SizedBox(height: 12),
                    _DashboardTaskPanel(task: task),
                  ],
                  const SizedBox(height: 18),
                  const Text(
                    'Calls',
                    style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
                  ),
                  const SizedBox(height: 10),
                  if (_error != null) ...[
                    _ErrorBanner(message: _error!),
                    const SizedBox(height: 12),
                  ],
                  if (AppConfig.isAndroid) ...[
                    FutureBuilder<bool>(
                      future: NativeBridges.shouldShowSetupPrompt(),
                      builder: (context, snap) {
                        if (snap.data != true) return const SizedBox.shrink();
                        return Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: _WhatsAppBanner(
                            onTap: () => widget.onNavigate('whatsapp_setup'),
                          ),
                        );
                      },
                    ),
                    if (((_pending['pendingCallRegistrations'] as int?) ?? 0) +
                            ((_pending['pendingWhatsAppCalls'] as int?) ?? 0) >
                        0)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: _PendingSyncCard(pending: _pending),
                      ),
                  ],
                  _DateRangeChips(
                    selected: _range,
                    onSelect: (r) => setState(() => _range = r),
                  ),
                  if (showStaffPicker) ...[
                    const SizedBox(height: 14),
                    _StaffSelector(
                      options: _staffOptions,
                      selectedId: _selectedStaffId,
                      onSelect: (id) => setState(() => _selectedStaffId = id),
                    ),
                    const SizedBox(height: 10),
                    _ViewingContext(
                      selectedStaffId: _selectedStaffId,
                      staffName: _staffOptions
                          .where((s) => s.id == _selectedStaffId)
                          .map((s) => s.name)
                          .firstOrNull,
                    ),
                  ],
                  const SizedBox(height: 14),
                  _KpiGrid(kpis: c.kpis),
                  const SizedBox(height: 16),
                  _DonutCard(kpis: c.kpis),
                  const SizedBox(height: 16),
                  _CallsPerDayCard(series: c.series),
                  if (_isAdmin || c.leaderboard.length > 1) ...[
                    const SizedBox(height: 16),
                    _LeaderboardCard(
                      entries: c.leaderboard,
                      onSelectStaff: (id) =>
                          setState(() => _selectedStaffId = id),
                    ),
                  ],
                  const SizedBox(height: 16),
                  _RecentCallsCard(rows: c.recent),
                  if (!AppConfig.isAndroid)
                    const Padding(
                      padding: EdgeInsets.only(top: 20),
                      child: Text(
                        'Showing CRM call history. Device sync is Android-only.',
                        style: TextStyle(color: AppColors.textSec, fontSize: 12),
                      ),
                    ),
                ],
              ),
      ),
    );
  }
}

class _DashboardTask {
  const _DashboardTask({
    required this.title,
    required this.body,
    required this.dueAt,
    required this.status,
    required this.contactName,
    required this.isReminder,
  });

  final String title;
  final String body;
  final DateTime? dueAt;
  final String status;
  final String contactName;
  final bool isReminder;

  factory _DashboardTask.fromMap(Map<String, dynamic> map) {
    return _DashboardTask(
      title: '${map['title'] ?? ''}'.trim(),
      body: '${map['body'] ?? ''}'.trim(),
      dueAt: DateTime.tryParse('${map['due_at'] ?? ''}'),
      status: '${map['status'] ?? 'open'}'.toLowerCase().trim(),
      contactName: '${map['contact_name'] ?? ''}'.trim(),
      isReminder: map['is_reminder'] == true,
    );
  }
}

class _TodayTaskCards extends StatelessWidget {
  const _TodayTaskCards({required this.tasks, required this.onOpen});

  final List<_DashboardTask> tasks;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 108,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: tasks.length,
        separatorBuilder: (_, __) => const SizedBox(width: 10),
        itemBuilder: (context, index) => _CompactTaskCard(task: tasks[index], onTap: onOpen),
      ),
    );
  }
}

class _CompactTaskCard extends StatelessWidget {
  const _CompactTaskCard({required this.task, required this.onTap});

  final _DashboardTask task;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final due = task.dueAt?.toLocal();
    final time = due == null ? '' : DateFormat('h:mm a').format(due);
    return SizedBox(
      width: 220,
      child: Material(
        color: Colors.white,
        surfaceTintColor: Colors.transparent,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(14),
          child: Container(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(14),
              boxShadow: const [
                BoxShadow(color: Color(0x14000000), blurRadius: 8, offset: Offset(0, 2)),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    if (time.isNotEmpty)
                      Text(time, style: const TextStyle(color: AppColors.textSec, fontSize: 11)),
                    const Spacer(),
                    Text(
                      _taskStatusLabel(task.status),
                      style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  task.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                ),
                if (task.body.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(
                    task.body,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: AppColors.textSec, fontSize: 12, height: 1.25),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

String _taskStatusLabel(String status) {
  return switch (status) {
    'in_progress' || 'in progress' => 'In progress',
    'completed' => 'Completed',
    'cancelled' => 'Cancelled',
    _ => 'Open',
  };
}

class _DashboardTaskPanel extends StatelessWidget {
  const _DashboardTaskPanel({required this.task});

  final SelectedDashboardTask task;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: const [
          BoxShadow(color: Color(0x14000000), blurRadius: 8, offset: Offset(0, 2)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(task.title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 4),
          Text(task.dueLabel, style: const TextStyle(color: AppColors.textSec, fontSize: 12)),
          const SizedBox(height: 4),
          Text(task.statusLabel, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
          if (task.body.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(task.body, style: const TextStyle(color: AppColors.textSec, fontSize: 13, height: 1.3)),
          ],
          if (task.contactName.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(task.contactName, style: const TextStyle(fontSize: 12, color: AppColors.textGray)),
          ],
          if (task.isReminder) ...[
            const SizedBox(height: 8),
            const Text('Reminder', style: TextStyle(color: Color(0xFFF59E0B), fontSize: 12, fontWeight: FontWeight.w600)),
          ],
        ],
      ),
    );
  }
}

class _TaskStatusCounts extends StatelessWidget {
  const _TaskStatusCounts({
    required this.open,
    required this.inProgress,
    required this.completed,
  });

  final int open;
  final int inProgress;
  final int completed;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          _StatusCountChip(count: open, label: 'Open', filled: true),
          const SizedBox(width: 8),
          _StatusCountChip(count: inProgress, label: 'In Progress'),
          const SizedBox(width: 8),
          _StatusCountChip(count: completed, label: 'Completed'),
        ],
      ),
    );
  }
}

class _StatusCountChip extends StatelessWidget {
  const _StatusCountChip({
    required this.count,
    required this.label,
    this.filled = false,
  });

  final int count;
  final String label;
  final bool filled;

  static const _filledBg = Color(0xFFD4E07A);
  static const _filledFg = Color(0xFF3D5214);
  static const _idleFg = Color(0xFF374151);
  static const _idleBorder = Color(0xFFE5E7EB);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      decoration: BoxDecoration(
        color: filled ? _filledBg : Colors.white,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: filled ? _filledBg : _idleBorder),
      ),
      child: Text(
        '$count $label',
        style: TextStyle(
          color: filled ? _filledFg : _idleFg,
          fontWeight: FontWeight.w600,
          fontSize: 13,
        ),
      ),
    );
  }
}

// ───────────────────────── models / compute ─────────────────────────

class _StaffOption {
  _StaffOption({required this.id, required this.name});
  final String id;
  final String name;
}

class _CallRow {
  _CallRow({
    required this.userId,
    required this.userName,
    required this.contactName,
    required this.phone,
    required this.direction,
    required this.durationSec,
    required this.startedAt,
  });

  final String? userId;
  final String? userName;
  final String contactName;
  final String phone;
  final String direction;
  final int durationSec;
  final DateTime? startedAt;

  bool get isMissed => direction.contains('MISS');
  bool get isOutgoing => direction.contains('OUT');
  bool get isIncoming => !isMissed && !isOutgoing && direction.contains('IN');
  bool get isConnected => !isMissed && durationSec > 0;

  static _CallRow? fromApi(Map<String, dynamic> raw) {
    final startedIso =
        '${raw['started_at'] ?? raw['startedAt'] ?? ''}'.trim();
    if (startedIso.isEmpty) return null;
    DateTime? started;
    try {
      started = DateTime.parse(startedIso).toLocal();
    } catch (_) {
      return null;
    }
    final dir =
        '${raw['direction'] ?? raw['call_direction'] ?? ''}'.toUpperCase();
    final phone =
        '${raw['phone_e164'] ?? raw['phoneE164'] ?? raw['phone_raw'] ?? raw['phone'] ?? ''}'
            .trim();
    final name =
        '${raw['contact_name'] ?? raw['contactName'] ?? phone}'.trim();
    final uid =
        '${raw['user_id'] ?? raw['userId'] ?? raw['staff_user_id'] ?? ''}'
            .trim();
    final uname =
        '${raw['user_name'] ?? raw['userName'] ?? raw['staff_user_name'] ?? ''}'
            .trim();
    final dur = raw['duration_sec'] ?? raw['durationSec'] ?? 0;
    return _CallRow(
      userId: uid.isEmpty ? null : uid,
      userName: uname.isEmpty ? null : uname,
      contactName: name.isEmpty ? 'Unknown' : name,
      phone: phone,
      direction: dir,
      durationSec: dur is int ? dur : int.tryParse('$dur') ?? 0,
      startedAt: started,
    );
  }
}

class _Kpis {
  const _Kpis({
    this.totalCalls = 0,
    this.incoming = 0,
    this.outgoing = 0,
    this.missed = 0,
    this.avgDurationSec = 0,
    this.talkTimeSec = 0,
    this.avgPerStaffHours = 0,
    this.totalDeltaPct = 0,
    this.outgoingDelta = 0,
    this.avgDurationDeltaSec = 0,
  });

  final int totalCalls;
  final int incoming;
  final int outgoing;
  final int missed;
  final int avgDurationSec;
  final int talkTimeSec;
  final double avgPerStaffHours;
  final int totalDeltaPct;
  final int outgoingDelta;
  final int avgDurationDeltaSec;
}

class _DaySeries {
  const _DaySeries({
    required this.labels,
    required this.incoming,
    required this.outgoing,
    required this.missed,
  });

  final List<String> labels;
  final List<int> incoming;
  final List<int> outgoing;
  final List<int> missed;
}

class _StaffStat {
  _StaffStat({
    required this.userId,
    required this.name,
    required this.initials,
    required this.totalCalls,
    required this.connectRatePct,
  });

  final String userId;
  final String name;
  final String initials;
  final int totalCalls;
  final int connectRatePct;
}

class _DashboardComputed {
  _DashboardComputed({
    required this.kpis,
    required this.series,
    required this.leaderboard,
    required this.recent,
  });

  final _Kpis kpis;
  final _DaySeries series;
  final List<_StaffStat> leaderboard;
  final List<_CallRow> recent;

  factory _DashboardComputed.from({
    required List<_CallRow> rows,
    required _DateRange range,
    required String? selectedStaffId,
    required List<_StaffOption> staffOptions,
  }) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    late final DateTime startDate;
    late final DateTime endDate;
    switch (range) {
      case _DateRange.today:
        startDate = today;
        endDate = today;
      case _DateRange.last7:
        startDate = today.subtract(const Duration(days: 6));
        endDate = today;
      case _DateRange.last30:
        startDate = today.subtract(const Duration(days: 29));
        endDate = today;
    }

    bool inScope(_CallRow row) {
      final d = row.startedAt;
      if (d == null) return false;
      final day = DateTime(d.year, d.month, d.day);
      if (day.isBefore(startDate) || day.isAfter(endDate)) return false;
      final selected = selectedStaffId?.trim();
      if (selected == null || selected.isEmpty) return true;
      return row.userId == selected;
    }

    final scoped = rows.where(inScope).toList();
    final incoming = scoped.where((r) => r.isIncoming).length;
    final outgoing = scoped.where((r) => r.isOutgoing).length;
    final missed = scoped.where((r) => r.isMissed).length;
    final connected = scoped.where((r) => r.isConnected).toList();
    final avgDur = connected.isEmpty
        ? 0
        : connected.fold<int>(0, (s, r) => s + r.durationSec) ~/
            connected.length;
    final talkTime =
        connected.fold<int>(0, (s, r) => s + r.durationSec);
    final staffCount = scoped
        .map((r) => r.userId)
        .whereType<String>()
        .where((id) => id.isNotEmpty)
        .toSet()
        .length
        .clamp(1, 9999);
    final avgPerStaffHours = (talkTime / 3600.0) / staffCount;

    final periodDays = endDate.difference(startDate).inDays + 1;
    final prevStart = startDate.subtract(Duration(days: periodDays));
    final prevEnd = startDate.subtract(const Duration(days: 1));

    bool inPrev(_CallRow row) {
      final d = row.startedAt;
      if (d == null) return false;
      final day = DateTime(d.year, d.month, d.day);
      if (day.isBefore(prevStart) || day.isAfter(prevEnd)) return false;
      final selected = selectedStaffId?.trim();
      if (selected == null || selected.isEmpty) return true;
      return row.userId == selected;
    }

    final prev = rows.where(inPrev).toList();
    final prevOut = prev.where((r) => r.isOutgoing).length;
    final prevConnected = prev.where((r) => r.isConnected).toList();
    final prevAvg = prevConnected.isEmpty
        ? 0
        : prevConnected.fold<int>(0, (s, r) => s + r.durationSec) ~/
            prevConnected.length;

    int pctDelta(int current, int previous) {
      if (previous == 0) return current == 0 ? 0 : 100;
      return ((current - previous) * 100 / previous).round();
    }

    final bucketCount = periodDays.clamp(1, 30);
    final labels = <String>[];
    final inSeries = List<int>.filled(bucketCount, 0);
    final outSeries = List<int>.filled(bucketCount, 0);
    final missSeries = List<int>.filled(bucketCount, 0);
    for (var i = 0; i < bucketCount; i++) {
      final day = startDate.add(Duration(days: i));
      if (bucketCount == 1) {
        labels.add('Today');
      } else if (bucketCount == 7) {
        labels.add(DateFormat('E').format(day).substring(0, 3));
      } else {
        labels.add('${day.day}');
      }
    }
    for (final row in scoped) {
      final d = row.startedAt!;
      final day = DateTime(d.year, d.month, d.day);
      final idx = day.difference(startDate).inDays;
      if (idx < 0 || idx >= bucketCount) continue;
      if (row.isIncoming) {
        inSeries[idx]++;
      } else if (row.isOutgoing) {
        outSeries[idx]++;
      } else if (row.isMissed) {
        missSeries[idx]++;
      }
    }

    final nameById = {for (final s in staffOptions) s.id: s.name};
    final leaderboard = scoped
        .where((r) => r.userId != null && r.userId!.isNotEmpty)
        .fold<Map<String, List<_CallRow>>>({}, (map, row) {
          map.putIfAbsent(row.userId!, () => []).add(row);
          return map;
        })
        .entries
        .map((e) {
          final name = nameById[e.key] ?? e.value.first.userName ?? 'Unknown';
          final total = e.value.length;
          final conn = e.value.where((r) => r.isConnected).length;
          final rate = total == 0 ? 0 : (conn * 100 ~/ total);
          return _StaffStat(
            userId: e.key,
            name: name,
            initials: _initials(name),
            totalCalls: total,
            connectRatePct: rate,
          );
        })
        .toList()
      ..sort((a, b) => b.totalCalls.compareTo(a.totalCalls));

    final recent = [...scoped]
      ..sort(
        (a, b) => (b.startedAt ?? DateTime(0))
            .compareTo(a.startedAt ?? DateTime(0)),
      );

    return _DashboardComputed(
      kpis: _Kpis(
        totalCalls: scoped.length,
        incoming: incoming,
        outgoing: outgoing,
        missed: missed,
        avgDurationSec: avgDur,
        talkTimeSec: talkTime,
        avgPerStaffHours: avgPerStaffHours,
        totalDeltaPct: pctDelta(scoped.length, prev.length),
        outgoingDelta: pctDelta(outgoing, prevOut),
        avgDurationDeltaSec: avgDur - prevAvg,
      ),
      series: _DaySeries(
        labels: labels,
        incoming: inSeries,
        outgoing: outSeries,
        missed: missSeries,
      ),
      leaderboard: leaderboard.take(5).toList(),
      recent: recent.take(8).toList(),
    );
  }
}

String _initials(String name) {
  final parts = name.trim().split(RegExp(r'\s+'));
  if (parts.isEmpty || parts.first.isEmpty) return '?';
  if (parts.length == 1) return parts.first.substring(0, math.min(2, parts.first.length)).toUpperCase();
  return ('${parts[0][0]}${parts[1][0]}').toUpperCase();
}

String _formatDur(int sec) {
  if (sec <= 0) return '0s';
  final m = sec ~/ 60;
  final s = sec % 60;
  if (m <= 0) return '${s}s';
  return '${m}m ${s.toString().padLeft(2, '0')}s';
}

String _formatTalkTime(int sec) {
  if (sec < 60) return '${sec}s';
  final h = sec ~/ 3600;
  final m = (sec % 3600) ~/ 60;
  if (h > 0) return '${h}h ${m}m';
  return '${m}m';
}

String _pctDelta(int pct) {
  if (pct == 0) return 'No change vs last period';
  final arrow = pct > 0 ? '↑' : '↓';
  return '$arrow ${pct.abs()}% vs last period';
}

String _secDelta(int sec) {
  if (sec == 0) return 'No change vs last period';
  final arrow = sec > 0 ? '↑' : '↓';
  return '$arrow ${sec.abs()}s vs last period';
}

// ───────────────────────── widgets ─────────────────────────

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFEBEE),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(message, style: const TextStyle(color: AppColors.errorRed)),
    );
  }
}

class _WhatsAppBanner extends StatelessWidget {
  const _WhatsAppBanner({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.lightIndigo,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: const Padding(
          padding: EdgeInsets.all(14),
          child: Row(
            children: [
              Icon(Icons.chat, color: AppColors.primaryBlue),
              SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Enable WhatsApp call tracking',
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                    Text(
                      'Turn on the accessibility service',
                      style: TextStyle(color: AppColors.textSec, fontSize: 12),
                    ),
                  ],
                ),
              ),
              Icon(Icons.chevron_right, color: AppColors.textSec),
            ],
          ),
        ),
      ),
    );
  }
}

class _PendingSyncCard extends StatelessWidget {
  const _PendingSyncCard({required this.pending});
  final Map<String, dynamic> pending;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFE2E8F0)),
      ),
      child: Row(
        children: [
          const Icon(Icons.cloud_upload_outlined, color: AppColors.primaryBlue),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              'Sync pending · Calls: ${pending['pendingCallRegistrations'] ?? 0} · '
              'WhatsApp: ${pending['pendingWhatsAppCalls'] ?? 0}',
              style: const TextStyle(fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

class _DateRangeChips extends StatelessWidget {
  const _DateRangeChips({required this.selected, required this.onSelect});

  final _DateRange selected;
  final ValueChanged<_DateRange> onSelect;

  @override
  Widget build(BuildContext context) {
    Widget chip(_DateRange range, String label) {
      final sel = selected == range;
      return GestureDetector(
        onTap: () => onSelect(range),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
          decoration: BoxDecoration(
            color: sel ? AppColors.darkBlue : const Color(0xFFF1F5F9),
            borderRadius: BorderRadius.circular(22),
          ),
          child: Text(
            label,
            style: TextStyle(
              color: sel ? Colors.white : AppColors.textSec,
              fontWeight: FontWeight.bold,
              fontSize: 13,
            ),
          ),
        ),
      );
    }

    return Row(
      children: [
        chip(_DateRange.today, 'Today'),
        const SizedBox(width: 10),
        chip(_DateRange.last7, '7D'),
        const SizedBox(width: 10),
        chip(_DateRange.last30, '30D'),
      ],
    );
  }
}

class _StaffSelector extends StatelessWidget {
  const _StaffSelector({
    required this.options,
    required this.selectedId,
    required this.onSelect,
  });

  final List<_StaffOption> options;
  final String? selectedId;
  final ValueChanged<String?> onSelect;

  @override
  Widget build(BuildContext context) {
    final label = selectedId == null
        ? 'All'
        : options
                .where((o) => o.id == selectedId)
                .map((o) => o.name)
                .firstOrNull ??
            'User';

    return PopupMenuButton<String?>(
      onSelected: onSelect,
      offset: const Offset(0, 48),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      itemBuilder: (context) => [
        const PopupMenuItem<String?>(
          value: null,
          child: Text('All', style: TextStyle(fontWeight: FontWeight.w600)),
        ),
        ...options.map(
          (o) => PopupMenuItem<String?>(
            value: o.id,
            child: Text(o.name),
          ),
        ),
      ],
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: const Color(0xFFE2E8F0)),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                label,
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                  color: AppColors.textMain,
                ),
              ),
            ),
            const Icon(Icons.keyboard_arrow_down, color: AppColors.textSec),
          ],
        ),
      ),
    );
  }
}

class _ViewingContext extends StatelessWidget {
  const _ViewingContext({
    required this.selectedStaffId,
    required this.staffName,
  });

  final String? selectedStaffId;
  final String? staffName;

  @override
  Widget build(BuildContext context) {
    final all = selectedStaffId == null;
    final tint = all ? AppColors.darkBlue : AppColors.primaryBlue;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFFE2E8F0)),
      ),
      child: Row(
        children: [
          Container(
            width: 30,
            height: 30,
            decoration: BoxDecoration(
              color: tint.withValues(alpha: 0.14),
              shape: BoxShape.circle,
            ),
            child: Icon(
              all ? Icons.groups : Icons.person,
              size: 16,
              color: tint,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'VIEWING',
                  style: TextStyle(
                    color: AppColors.textSec,
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  all ? 'All users' : (staffName ?? 'User'),
                  style: const TextStyle(
                    color: AppColors.textMain,
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _KpiGrid extends StatelessWidget {
  const _KpiGrid({required this.kpis});
  final _Kpis kpis;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: _KpiCard(
                icon: Icons.phone,
                iconColor: _DashboardScreenState._kpiBlue,
                label: 'TOTAL CALLS',
                value: '${kpis.totalCalls}',
                footer: _pctDelta(kpis.totalDeltaPct),
                footerColor: kpis.totalDeltaPct >= 0
                    ? _DashboardScreenState._kpiGreen
                    : _DashboardScreenState._kpiRed,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _KpiCard(
                icon: Icons.timer_outlined,
                iconColor: AppColors.textMain,
                label: 'TALK TIME',
                value: _formatTalkTime(kpis.talkTimeSec),
                footer:
                    'Avg ${kpis.avgPerStaffHours.toStringAsFixed(1)}h/staff',
                footerColor: AppColors.textSec,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(
              child: _KpiCard(
                icon: Icons.schedule,
                iconColor: _DashboardScreenState._kpiBlue,
                label: 'AVG DURATION',
                value: _formatDur(kpis.avgDurationSec),
                footer: _secDelta(kpis.avgDurationDeltaSec),
                footerColor: kpis.avgDurationDeltaSec >= 0
                    ? _DashboardScreenState._kpiGreen
                    : _DashboardScreenState._kpiRed,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _KpiCard(
                icon: Icons.call_made,
                iconColor: _DashboardScreenState._kpiOrange,
                label: 'OUTGOING CALLS',
                value: '${kpis.outgoing}',
                footer: _pctDelta(kpis.outgoingDelta),
                footerColor: kpis.outgoingDelta >= 0
                    ? _DashboardScreenState._kpiGreen
                    : _DashboardScreenState._kpiRed,
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _KpiCard extends StatelessWidget {
  const _KpiCard({
    required this.icon,
    required this.iconColor,
    required this.label,
    required this.value,
    required this.footer,
    required this.footerColor,
  });

  final IconData icon;
  final Color iconColor;
  final String label;
  final String value;
  final String footer;
  final Color footerColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 132,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 8,
            offset: Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 18, color: iconColor),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  label,
                  style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                    color: AppColors.textMain,
                  ),
                ),
              ),
            ],
          ),
          const Spacer(),
          Text(
            value,
            style: const TextStyle(
              fontSize: 26,
              fontWeight: FontWeight.bold,
              color: AppColors.textMain,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            footer,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: footerColor,
            ),
          ),
        ],
      ),
    );
  }
}

class _DonutCard extends StatelessWidget {
  const _DonutCard({required this.kpis});
  final _Kpis kpis;

  @override
  Widget build(BuildContext context) {
    final total = kpis.totalCalls;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 8,
            offset: Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Expanded(
                child: Text(
                  'Calls by Direction',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                    color: AppColors.textMain,
                  ),
                ),
              ),
              Icon(Icons.more_horiz, color: AppColors.textSec),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              SizedBox(
                width: 140,
                height: 140,
                child: CustomPaint(
                  painter: _DonutPainter(
                    incoming: kpis.incoming,
                    outgoing: kpis.outgoing,
                    missed: kpis.missed,
                  ),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          '$total',
                          style: const TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 22,
                            color: AppColors.textMain,
                          ),
                        ),
                        const Text(
                          'Total',
                          style: TextStyle(
                            color: AppColors.textSec,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  children: [
                    _LegendRow(
                      color: _DashboardScreenState._kpiGreen,
                      label: 'Incoming',
                      count: kpis.incoming,
                      pct: total == 0 ? 0 : (kpis.incoming * 100 / total).round(),
                    ),
                    const SizedBox(height: 12),
                    _LegendRow(
                      color: _DashboardScreenState._kpiBlue,
                      label: 'Outgoing',
                      count: kpis.outgoing,
                      pct: total == 0 ? 0 : (kpis.outgoing * 100 / total).round(),
                    ),
                    const SizedBox(height: 12),
                    _LegendRow(
                      color: _DashboardScreenState._kpiRed,
                      label: 'Missed',
                      count: kpis.missed,
                      pct: total == 0 ? 0 : (kpis.missed * 100 / total).round(),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _LegendRow extends StatelessWidget {
  const _LegendRow({
    required this.color,
    required this.label,
    required this.count,
    required this.pct,
  });

  final Color color;
  final String label;
  final int count;
  final int pct;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 13,
                  color: AppColors.textMain,
                ),
              ),
              Text(
                '$count calls',
                style: const TextStyle(color: AppColors.textSec, fontSize: 11),
              ),
            ],
          ),
        ),
        Text(
          '$pct%',
          style: const TextStyle(
            fontWeight: FontWeight.bold,
            color: AppColors.textMain,
          ),
        ),
      ],
    );
  }
}

class _DonutPainter extends CustomPainter {
  _DonutPainter({
    required this.incoming,
    required this.outgoing,
    required this.missed,
  });

  final int incoming;
  final int outgoing;
  final int missed;

  @override
  void paint(Canvas canvas, Size size) {
    final total = incoming + outgoing + missed;
    final center = Offset(size.width / 2, size.height / 2);
    final radius = math.min(size.width, size.height) / 2;
    final stroke = radius * 0.28;
    final rect = Rect.fromCircle(center: center, radius: radius - stroke / 2);
    final bg = Paint()
      ..color = const Color(0xFFF1F5F9)
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round;

    canvas.drawArc(rect, 0, math.pi * 2, false, bg);
    if (total <= 0) return;

    void drawSeg(int count, Color color, double start) {
      if (count <= 0) return;
      final sweep = (count / total) * math.pi * 2;
      final paint = Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.butt;
      canvas.drawArc(rect, start, sweep, false, paint);
    }

    var angle = -math.pi / 2;
    drawSeg(outgoing, _DashboardScreenState._kpiBlue, angle);
    angle += (outgoing / total) * math.pi * 2;
    drawSeg(incoming, _DashboardScreenState._kpiGreen, angle);
    angle += (incoming / total) * math.pi * 2;
    drawSeg(missed, _DashboardScreenState._kpiRed, angle);
  }

  @override
  bool shouldRepaint(covariant _DonutPainter oldDelegate) =>
      oldDelegate.incoming != incoming ||
      oldDelegate.outgoing != outgoing ||
      oldDelegate.missed != missed;
}

class _CallsPerDayCard extends StatelessWidget {
  const _CallsPerDayCard({required this.series});
  final _DaySeries series;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 8,
            offset: Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Calls per Day',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                    color: AppColors.textMain,
                  ),
                ),
              ),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: const Color(0xFFDCFCE7),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: const Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.circle, size: 7, color: Color(0xFF16A34A)),
                    SizedBox(width: 6),
                    Text(
                      'LIVE',
                      style: TextStyle(
                        color: Color(0xFF166534),
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SizedBox(
            height: 160,
            child: CustomPaint(
              painter: _BarPainter(series: series),
              child: const SizedBox.expand(),
            ),
          ),
          const SizedBox(height: 6),
          Row(
            children: series.labels
                .map(
                  (l) => Expanded(
                    child: Text(
                      l,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        color: AppColors.textSec,
                        fontSize: 10,
                      ),
                    ),
                  ),
                )
                .toList(),
          ),
          const SizedBox(height: 12),
          const Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              _DotLabel(color: _DashboardScreenState._kpiGreen, label: 'Incoming'),
              SizedBox(width: 16),
              _DotLabel(color: _DashboardScreenState._kpiBlue, label: 'Outgoing'),
              SizedBox(width: 16),
              _DotLabel(color: _DashboardScreenState._kpiRed, label: 'Missed'),
            ],
          ),
        ],
      ),
    );
  }
}

class _DotLabel extends StatelessWidget {
  const _DotLabel({required this.color, required this.label});
  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Text(
          label,
          style: const TextStyle(color: AppColors.textSec, fontSize: 11),
        ),
      ],
    );
  }
}

class _BarPainter extends CustomPainter {
  _BarPainter({required this.series});
  final _DaySeries series;

  @override
  void paint(Canvas canvas, Size size) {
    final n = series.labels.length;
    if (n == 0) return;
    var maxY = 1;
    for (var i = 0; i < n; i++) {
      final t = series.incoming[i] + series.outgoing[i] + series.missed[i];
      if (t > maxY) maxY = t;
    }
    final slot = size.width / n;
    final barW = math.min(14.0, slot * 0.45);

    for (var i = 0; i < n; i++) {
      final cx = slot * i + slot / 2;
      var y = size.height;
      void stack(int count, Color color) {
        if (count <= 0) return;
        final h = (count / maxY) * size.height;
        final rect = RRect.fromRectAndRadius(
          Rect.fromLTWH(cx - barW / 2, y - h, barW, h),
          const Radius.circular(4),
        );
        canvas.drawRRect(rect, Paint()..color = color);
        y -= h;
      }

      stack(series.incoming[i], _DashboardScreenState._kpiGreen);
      stack(series.outgoing[i], _DashboardScreenState._kpiBlue);
      stack(series.missed[i], _DashboardScreenState._kpiRed);
    }
  }

  @override
  bool shouldRepaint(covariant _BarPainter oldDelegate) => true;
}

class _LeaderboardCard extends StatelessWidget {
  const _LeaderboardCard({
    required this.entries,
    required this.onSelectStaff,
  });

  final List<_StaffStat> entries;
  final ValueChanged<String> onSelectStaff;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 8,
            offset: Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Expanded(
                child: Text(
                  'Top Staff Leaderboard',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                    color: AppColors.textMain,
                  ),
                ),
              ),
              Text(
                'View All',
                style: TextStyle(
                  color: AppColors.primaryBlue,
                  fontWeight: FontWeight.w600,
                  fontSize: 12,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (entries.isEmpty)
            const Text(
              'No staff data yet',
              style: TextStyle(color: AppColors.textSec, fontSize: 12),
            )
          else
            ...List.generate(entries.length, (idx) {
              final s = entries[idx];
              final rank = idx + 1;
              final rankColor = switch (rank) {
                1 => _DashboardScreenState._kpiOrange,
                2 => const Color(0xFF94A3B8),
                3 => const Color(0xFFB07B5C),
                _ => AppColors.textSec,
              };
              return Column(
                children: [
                  InkWell(
                    onTap: () => onSelectStaff(s.userId),
                    borderRadius: BorderRadius.circular(10),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      child: Row(
                        children: [
                          Container(
                            width: 44,
                            height: 44,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: AppColors.primaryBlue.withValues(alpha: 0.12),
                              border: Border.all(color: rankColor, width: 2),
                            ),
                            alignment: Alignment.center,
                            child: Text(
                              s.initials,
                              style: const TextStyle(
                                color: AppColors.primaryBlue,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Row(
                              children: [
                                Text(
                                  '#$rank',
                                  style: TextStyle(
                                    color: rankColor,
                                    fontWeight: FontWeight.bold,
                                    fontSize: 12,
                                  ),
                                ),
                                const SizedBox(width: 6),
                                Expanded(
                                  child: Text(
                                    s.name,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.bold,
                                      fontSize: 14,
                                      color: AppColors.textMain,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Text(
                            '${s.connectRatePct}%',
                            style: const TextStyle(
                              color: _DashboardScreenState._kpiGreen,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  if (idx != entries.length - 1)
                    const Divider(color: Color(0xFFF1F5F9), height: 1),
                ],
              );
            }),
        ],
      ),
    );
  }
}

class _RecentCallsCard extends StatelessWidget {
  const _RecentCallsCard({required this.rows});
  final List<_CallRow> rows;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 8,
            offset: Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Recent Calls',
            style: TextStyle(
              fontWeight: FontWeight.bold,
              fontSize: 16,
              color: AppColors.textMain,
            ),
          ),
          const SizedBox(height: 10),
          if (rows.isEmpty)
            const Text(
              'No calls in this period',
              style: TextStyle(color: AppColors.textSec, fontSize: 12),
            )
          else
            ...rows.map((r) {
              final color = r.isMissed
                  ? _DashboardScreenState._kpiRed
                  : r.isIncoming
                      ? _DashboardScreenState._kpiGreen
                      : _DashboardScreenState._kpiBlue;
              final icon = r.isMissed
                  ? Icons.call_missed
                  : r.isIncoming
                      ? Icons.call_received
                      : Icons.call_made;
              final when = r.startedAt == null
                  ? ''
                  : DateFormat('MMM d · h:mm a').format(r.startedAt!);
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  children: [
                    Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: color.withValues(alpha: 0.12),
                        shape: BoxShape.circle,
                      ),
                      child: Icon(icon, size: 18, color: color),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            r.contactName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              color: AppColors.textMain,
                            ),
                          ),
                          Text(
                            when,
                            style: const TextStyle(
                              color: AppColors.textSec,
                              fontSize: 11,
                            ),
                          ),
                        ],
                      ),
                    ),
                    Text(
                      _formatDur(r.durationSec),
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 12,
                        color: AppColors.textMain,
                      ),
                    ),
                  ],
                ),
              );
            }),
        ],
      ),
    );
  }
}
