import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mycrmsync/data/api_client.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:mycrmsync/ui/widgets/app_shell.dart';
import 'package:mycrmsync/ui/widgets/contact_list_card.dart';

enum _TaskScope { all, mine, everyone }

class TasksScreen extends ConsumerStatefulWidget {
  const TasksScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  ConsumerState<TasksScreen> createState() => _TasksScreenState();
}

class _TasksScreenState extends ConsumerState<TasksScreen> {
  bool _loading = true;
  String? _error;
  List<_TaskItem> _tasks = [];
  List<_Person> _users = [];
  int? _myId;
  DateTime _selectedDay = _dateOnly(DateTime.now());
  _TaskScope _scope = _TaskScope.all;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final api = ref.read(crmApiProvider);
      final session = ref.read(sessionProvider).valueOrNull;
      final results = await Future.wait([
        api.getTasks(status: 'all', kind: 'all'),
        api.getUsers(),
      ]);
      if (!mounted) return;
      setState(() {
        _tasks = (results[0]).map(_TaskItem.fromMap).toList();
        _users = (results[1]).map(_Person.fromMap).where((u) => u.id > 0).toList();
        _myId = session?.authUserId ?? int.tryParse(session?.effectiveUserId ?? '');
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e'.replaceFirst(RegExp(r'^Exception:\s*'), '');
        _loading = false;
      });
    }
  }

  bool _inScope(_TaskItem task) {
    switch (_scope) {
      case _TaskScope.mine:
        return _myId != null && task.assigneeIds.contains(_myId);
      case _TaskScope.everyone:
        return task.assigneeIds.isEmpty;
      case _TaskScope.all:
        return true;
    }
  }

  List<_TaskItem> get _visible {
    return _tasks.where((task) {
      final due = task.dueAt?.toLocal();
      if (due == null || _dateOnly(due) != _selectedDay) return false;
      return _inScope(task);
    }).toList()
      ..sort((a, b) => (a.dueAt ?? DateTime(0)).compareTo(b.dueAt ?? DateTime(0)));
  }

  List<_TaskItem> get _datedTasks {
    return _tasks.where((task) => task.dueAt != null && _inScope(task)).toList()
      ..sort((a, b) => a.dueAt!.compareTo(b.dueAt!));
  }

  Future<void> _openForm({_TaskItem? task}) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => _AddTaskDialog(
        api: ref.read(crmApiProvider),
        users: _users,
        existing: task,
      ),
    );
    if (saved == true) await _load();
  }

  Future<void> _changeStatus(_TaskItem task, String status) async {
    if (status == task.status) return;
    try {
      await ref.read(crmApiProvider).updateTaskStatus(task.id, status);
      if (mounted) await _load();
    } catch (e) {
      _snack('$e'.replaceFirst(RegExp(r'^Exception:\s*'), ''));
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  void _openTask(_TaskItem task) {
    if (task.isReminder) {
      _snack('Reminders are changed from the contact note.');
      return;
    }
    _openForm(task: task);
  }

  @override
  Widget build(BuildContext context) {
    final visible = _visible;
    final groups = _groupsOf(visible);
    return AppShell(
      title: 'My Tasks',
      currentRoute: 'tasks',
      onNavigate: widget.onNavigate,
      floatingActionButton: FloatingActionButton(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        onPressed: () => _openForm(),
        child: const Icon(Icons.add),
      ),
      child: RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
          children: [
            if (_datedTasks.isNotEmpty) ...[
              _DatedTaskCarousel(
                tasks: _datedTasks,
                onStatus: _changeStatus,
                onOpen: _openTask,
              ),
              const SizedBox(height: 16),
            ],
            _WeekStrip(
              selected: _selectedDay,
              onSelect: (day) => setState(() => _selectedDay = day),
            ),
            const SizedBox(height: 16),
            _ScopeChips(
              selected: _scope,
              onSelect: (scope) => setState(() => _scope = scope),
            ),
            const SizedBox(height: 18),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(_error!, style: const TextStyle(color: AppColors.errorRed)),
              ),
            if (_loading && _tasks.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 80),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (groups.isEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 48),
                child: Text(
                  'No tasks on ${_formatDue(_selectedDay)}',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: AppColors.textSec),
                ),
              )
            else
              for (final group in groups) ...[
                _SectionHeader(label: group.label, day: _selectedDay),
                const SizedBox(height: 10),
                for (final task in group.tasks) ...[
                  _TaskCard(
                    task: task,
                    onStatus: (status) => _changeStatus(task, status),
                    onTap: () => _openTask(task),
                  ),
                  const SizedBox(height: 12),
                ],
              ],
          ],
        ),
      ),
    );
  }
}

class _DayGroup {
  const _DayGroup(this.label, this.tasks);

  final String label;
  final List<_TaskItem> tasks;
}

List<_DayGroup> _groupsOf(List<_TaskItem> tasks) {
  final morning = <_TaskItem>[];
  final afternoon = <_TaskItem>[];
  final evening = <_TaskItem>[];
  for (final task in tasks) {
    final hour = task.dueAt?.toLocal().hour ?? 0;
    if (hour < 12) {
      morning.add(task);
    } else if (hour < 17) {
      afternoon.add(task);
    } else {
      evening.add(task);
    }
  }
  return [
    if (morning.isNotEmpty) _DayGroup('Morning', morning),
    if (afternoon.isNotEmpty) _DayGroup('Afternoon', afternoon),
    if (evening.isNotEmpty) _DayGroup('Evening', evening),
  ];
}

class _WeekStrip extends StatelessWidget {
  const _WeekStrip({required this.selected, required this.onSelect});

  final DateTime selected;
  final ValueChanged<DateTime> onSelect;

  @override
  Widget build(BuildContext context) {
    final start = selected.subtract(Duration(days: selected.weekday - DateTime.monday));
    final days = List.generate(7, (i) => _dateOnly(start.add(Duration(days: i))));
    return Column(
      children: [
        Row(
          children: [
            IconButton(
              onPressed: () => onSelect(selected.subtract(const Duration(days: 7))),
              icon: const Icon(Icons.chevron_left),
            ),
            Expanded(
              child: Text(
                _formatDue(selected),
                textAlign: TextAlign.center,
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
              ),
            ),
            IconButton(
              onPressed: () => onSelect(selected.add(const Duration(days: 7))),
              icon: const Icon(Icons.chevron_right),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Row(
          children: [
            for (final day in days)
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 3),
                  child: _DayPill(
                    day: day,
                    selected: day == selected,
                    onTap: () => onSelect(day),
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

class _DayPill extends StatelessWidget {
  const _DayPill({required this.day, required this.selected, required this.onTap});

  final DateTime day;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final fg = selected ? Colors.white : AppColors.textMain;
    return Material(
      color: selected ? Colors.black : Colors.white,
      borderRadius: BorderRadius.circular(22),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(22),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Column(
            children: [
              Text(
                DateFormat('E').format(day),
                style: TextStyle(fontSize: 11, color: selected ? Colors.white70 : AppColors.textSec),
              ),
              const SizedBox(height: 2),
              Text(
                '${day.day}',
                style: TextStyle(fontWeight: FontWeight.w700, color: fg),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ScopeChips extends StatelessWidget {
  const _ScopeChips({required this.selected, required this.onSelect});

  final _TaskScope selected;
  final ValueChanged<_TaskScope> onSelect;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _chip('All', _TaskScope.all, Icons.grid_view_rounded),
        const SizedBox(width: 8),
        _chip('Mine', _TaskScope.mine, Icons.person_outline),
        const SizedBox(width: 8),
        _chip('Everyone', _TaskScope.everyone, Icons.groups_outlined),
      ],
    );
  }

  Widget _chip(String label, _TaskScope scope, IconData icon) {
    final on = selected == scope;
    return Material(
      color: on ? Colors.black : Colors.white,
      borderRadius: BorderRadius.circular(24),
      child: InkWell(
        onTap: () => onSelect(scope),
        borderRadius: BorderRadius.circular(24),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(
            children: [
              Icon(icon, size: 16, color: on ? Colors.white : AppColors.textMain),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  fontWeight: FontWeight.w600,
                  color: on ? Colors.white : AppColors.textMain,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.label, required this.day});

  final String label;
  final DateTime day;

  @override
  Widget build(BuildContext context) {
    final today = _dateOnly(DateTime.now()) == day;
    final icon = switch (label) {
      'Morning' => Icons.wb_sunny_outlined,
      'Afternoon' => Icons.wb_cloudy_outlined,
      _ => Icons.nights_stay_outlined,
    };
    return Row(
      children: [
        Icon(icon, size: 18, color: AppColors.textSec),
        const SizedBox(width: 6),
        Text(label, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
        const Spacer(),
        Text(
          today ? 'Today' : _formatDue(day),
          style: const TextStyle(color: AppColors.textSec, fontSize: 13),
        ),
      ],
    );
  }
}

class _DatedTaskCarousel extends StatefulWidget {
  const _DatedTaskCarousel({
    required this.tasks,
    required this.onStatus,
    required this.onOpen,
  });

  final List<_TaskItem> tasks;
  final void Function(_TaskItem task, String status) onStatus;
  final ValueChanged<_TaskItem> onOpen;

  @override
  State<_DatedTaskCarousel> createState() => _DatedTaskCarouselState();
}

class _DatedTaskCarouselState extends State<_DatedTaskCarousel> {
  static const _cardWidth = 300.0;
  static const _gap = 12.0;

  final _controller = ScrollController();
  var _positioned = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToUpcoming());
  }

  @override
  void didUpdateWidget(covariant _DatedTaskCarousel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_positioned && oldWidget.tasks.isEmpty && widget.tasks.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToUpcoming());
    }
  }

  void _scrollToUpcoming() {
    if (!_controller.hasClients || widget.tasks.isEmpty) return;
    final today = _dateOnly(DateTime.now());
    var index = widget.tasks.indexWhere((task) {
      final due = task.dueAt?.toLocal();
      return due != null && !_dateOnly(due).isBefore(today);
    });
    if (index < 0) index = widget.tasks.length - 1;
    final target = index * (_cardWidth + _gap);
    final max = _controller.position.maxScrollExtent;
    _controller.jumpTo(target.clamp(0, max).toDouble());
    _positioned = true;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 176,
      child: ListView.separated(
        controller: _controller,
        scrollDirection: Axis.horizontal,
        itemCount: widget.tasks.length,
        separatorBuilder: (_, __) => const SizedBox(width: _gap),
        itemBuilder: (context, index) {
          final task = widget.tasks[index];
          return _DatedTaskCard(
            task: task,
            onStatus: (status) => widget.onStatus(task, status),
            onTap: () => widget.onOpen(task),
          );
        },
      ),
    );
  }
}

class _DatedTaskCard extends StatelessWidget {
  const _DatedTaskCard({
    required this.task,
    required this.onTap,
    required this.onStatus,
  });

  final _TaskItem task;
  final VoidCallback onTap;
  final ValueChanged<String> onStatus;

  @override
  Widget build(BuildContext context) {
    final due = task.dueAt?.toLocal();
    return SizedBox(
      width: _DatedTaskCarouselState._cardWidth,
      child: Material(
        color: Colors.white,
        surfaceTintColor: Colors.transparent,
        borderRadius: BorderRadius.circular(20),
        child: Container(
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(20),
            boxShadow: const [
              BoxShadow(color: Color(0x14000000), blurRadius: 16, offset: Offset(0, 6)),
            ],
          ),
          padding: const EdgeInsets.fromLTRB(14, 8, 4, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Text(
                        due == null ? 'No due date' : _formatDue(due, withTime: true),
                        style: const TextStyle(color: AppColors.textSec, fontSize: 12),
                      ),
                    ),
                  ),
                  _TaskStatusMenu(value: task.status, onChanged: onStatus),
                ],
              ),
              Expanded(
                child: InkWell(
                  onTap: onTap,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        task.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
                      ),
                      if (task.body.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          task.body,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: AppColors.textSec, fontSize: 13, height: 1.3),
                        ),
                      ],
                      if (task.contactName.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          task.contactName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 12, color: AppColors.textGray),
                        ),
                      ],
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Flexible(
                            child: _AssigneeAvatars(
                              names: task.assigneeNames,
                              everyone: task.assigneeIds.isEmpty,
                            ),
                          ),
                          if (task.isReminder) ...[
                            const SizedBox(width: 8),
                            const _Pill(label: 'Reminder', color: Color(0xFFF59E0B)),
                          ],
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TaskCard extends StatelessWidget {
  const _TaskCard({required this.task, required this.onTap, required this.onStatus});

  final _TaskItem task;
  final VoidCallback onTap;
  final ValueChanged<String> onStatus;

  @override
  Widget build(BuildContext context) {
    final due = task.dueAt?.toLocal();
    return Material(
      color: Colors.white,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      borderRadius: BorderRadius.circular(24),
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(24),
          boxShadow: const [
            BoxShadow(color: Color(0x14000000), blurRadius: 16, offset: Offset(0, 6)),
          ],
        ),
        padding: const EdgeInsets.fromLTRB(14, 8, 4, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: SizedBox(
                width: 52,
                child: Column(
                  children: [
                    Text(
                      due == null ? '—' : DateFormat('h:mm').format(due),
                      style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
                    ),
                    Text(
                      due == null ? '' : DateFormat('a').format(due),
                      style: const TextStyle(color: AppColors.textSec, fontSize: 12),
                    ),
                  ],
                ),
              ),
            ),
            Container(
              width: 1,
              height: 72,
              margin: const EdgeInsets.only(top: 6, left: 0),
              color: const Color(0xFFE8ECF3),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: InkWell(
                          onTap: onTap,
                          child: Padding(
                            padding: const EdgeInsets.only(top: 6),
                            child: Text(task.title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                          ),
                        ),
                      ),
                      _TaskStatusMenu(value: task.status, onChanged: onStatus),
                    ],
                  ),
                  InkWell(
                    onTap: onTap,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 2),
                        Text(
                          due == null ? 'No due date' : _formatDue(due, withTime: true),
                          style: const TextStyle(color: AppColors.textSec, fontSize: 12),
                        ),
                        if (task.body.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            task.body,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(color: AppColors.textSec, fontSize: 13, height: 1.3),
                          ),
                        ],
                        if (task.contactName.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(task.contactName, style: const TextStyle(fontSize: 12, color: AppColors.textGray)),
                        ],
                        const SizedBox(height: 8),
                        Row(
                          children: [
                            Flexible(
                              child: _AssigneeAvatars(
                                names: task.assigneeNames,
                                everyone: task.assigneeIds.isEmpty,
                              ),
                            ),
                            if (task.isReminder) ...[
                              const SizedBox(width: 8),
                              const _Pill(label: 'Reminder', color: Color(0xFFF59E0B)),
                            ],
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

const _taskStatuses = {
  'open': 'Open',
  'in_progress': 'In progress',
  'completed': 'Completed',
  'cancelled': 'Cancelled',
};

class _TaskStatusMenu extends StatelessWidget {
  const _TaskStatusMenu({required this.value, required this.onChanged});

  final String value;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final selected = _taskStatuses.containsKey(value) ? value : 'open';
    return PopupMenuButton<String>(
      tooltip: 'Change status',
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
      icon: const Icon(Icons.more_vert, size: 20, color: AppColors.textSec),
      onSelected: (next) {
        if (next != selected) onChanged(next);
      },
      itemBuilder: (context) => [
        for (final entry in _taskStatuses.entries)
          PopupMenuItem(
            value: entry.key,
            child: Row(
              children: [
                Icon(
                  Icons.check,
                  size: 18,
                  color: entry.key == selected ? AppColors.textMain : Colors.transparent,
                ),
                const SizedBox(width: 8),
                Text(entry.value),
              ],
            ),
          ),
      ],
    );
  }
}

class _StatusDropdown extends StatelessWidget {
  const _StatusDropdown({
    required this.value,
    required this.onChanged,
    this.expanded = false,
  });

  final String value;
  final ValueChanged<String> onChanged;
  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final selected = _taskStatuses.containsKey(value) ? value : 'open';
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        decoration: BoxDecoration(
          border: Border.all(color: const Color(0xFFCBD5E1)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            value: selected,
            isDense: true,
            isExpanded: expanded,
            borderRadius: BorderRadius.circular(8),
            items: [
              for (final entry in _taskStatuses.entries)
                DropdownMenuItem(
                  value: entry.key,
                  child: Text(entry.value, style: const TextStyle(fontSize: 14)),
                ),
            ],
            onChanged: (next) {
              if (next != null && next != selected) onChanged(next);
            },
          ),
        ),
      ),
    );
  }
}

class _AssigneeAvatars extends StatelessWidget {
  const _AssigneeAvatars({required this.names, required this.everyone});

  final List<String> names;
  final bool everyone;

  @override
  Widget build(BuildContext context) {
    if (everyone) return const _Pill(label: 'All', color: AppColors.textSec);
    final shown = names.take(3).toList();
    final extra = names.length - shown.length;
    return Row(
      children: [
        for (var i = 0; i < shown.length; i++)
          Transform.translate(
            offset: Offset(-8.0 * i, 0),
            child: CircleAvatar(
              radius: 14,
              backgroundColor: _avatarColor(i),
              child: Text(
                _initials(shown[i]),
                style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
              ),
            ),
          ),
        if (extra > 0)
          Transform.translate(
            offset: Offset(-8.0 * shown.length, 0),
            child: CircleAvatar(
              radius: 14,
              backgroundColor: const Color(0xFFE2E8F0),
              child: Text('+$extra', style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700)),
            ),
          ),
      ],
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(label, style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w600)),
    );
  }
}

class _AddTaskDialog extends StatefulWidget {
  const _AddTaskDialog({
    required this.api,
    required this.users,
    this.existing,
  });

  final CrmApi api;
  final List<_Person> users;
  final _TaskItem? existing;

  @override
  State<_AddTaskDialog> createState() => _AddTaskDialogState();
}

class _AddTaskDialogState extends State<_AddTaskDialog> {
  late final TextEditingController _title;
  late final TextEditingController _body;
  late final TextEditingController _contact;
  late DateTime? _due;
  late String _status;
  late List<int> _assigneeIds;
  String? _contactId;
  String _contactName = '';
  List<Map<String, dynamic>> _matches = [];
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final existing = widget.existing;
    _title = TextEditingController(text: existing?.title ?? '');
    _body = TextEditingController(text: existing?.body ?? '');
    _contactName = existing?.contactName ?? '';
    _contactId = existing?.contactId;
    _contact = TextEditingController(text: _contactName);
    _due = existing?.dueAt?.toLocal() ?? DateTime.now();
    _status = _taskStatuses.containsKey(existing?.status) ? existing!.status : 'open';
    _assigneeIds = List<int>.from(existing?.assigneeIds ?? const []);
  }

  @override
  void dispose() {
    _title.dispose();
    _body.dispose();
    _contact.dispose();
    super.dispose();
  }

  Future<void> _pickDue() async {
    final now = DateTime.now();
    final initial = _due ?? now;
    final date = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(now.year - 1),
      lastDate: DateTime(now.year + 5),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(initial),
    );
    if (time == null || !mounted) return;
    setState(() {
      _due = DateTime(date.year, date.month, date.day, time.hour, time.minute);
    });
  }

  Future<void> _searchContacts(String query) async {
    _contactId = null;
    _contactName = query.trim();
    if (query.trim().length < 2) {
      setState(() => _matches = []);
      return;
    }
    try {
      final matches = await widget.api.matchContacts(query: query.trim());
      if (!mounted) return;
      setState(() => _matches = matches);
    } catch (_) {
      if (mounted) setState(() => _matches = []);
    }
  }

  Future<void> _pickAssignees() async {
    final selected = Set<int>.from(_assigneeIds);
    final result = await showDialog<List<int>>(
      context: context,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setLocal) {
            return AlertDialog(
              title: const Text('Assign to'),
              content: SizedBox(
                width: double.maxFinite,
                child: widget.users.isEmpty
                    ? const Text('No users found for this company.')
                    : ListView(
                        shrinkWrap: true,
                        children: [
                          for (final user in widget.users)
                            CheckboxListTile(
                              value: selected.contains(user.id),
                              title: Text(user.name),
                              controlAffinity: ListTileControlAffinity.leading,
                              onChanged: (checked) {
                                setLocal(() {
                                  if (checked == true) {
                                    selected.add(user.id);
                                  } else {
                                    selected.remove(user.id);
                                  }
                                });
                              },
                            ),
                        ],
                      ),
              ),
              actions: [
                TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
                FilledButton(
                  onPressed: () => Navigator.pop(context, selected.toList()),
                  child: const Text('Done'),
                ),
              ],
            );
          },
        );
      },
    );
    if (result != null) setState(() => _assigneeIds = result);
  }

  Future<void> _save() async {
    final title = _title.text.trim();
    if (title.isEmpty) {
      setState(() => _error = 'A title is required.');
      return;
    }
    final due = _due;
    if (due == null) {
      setState(() => _error = 'Pick a due date and time.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final existing = widget.existing;
      if (existing == null) {
        await widget.api.createTask(
          title: title,
          body: _body.text,
          dueAt: due,
          contactId: _contactId,
          contactName: _contactName,
          assigneeIds: _assigneeIds,
          status: _status,
        );
      } else {
        await widget.api.updateTask(
          taskId: existing.id,
          title: title,
          body: _body.text,
          dueAt: due,
          contactId: _contactId,
          contactName: _contactName,
          assigneeIds: _assigneeIds,
          status: _status,
        );
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = '$e'.replaceFirst(RegExp(r'^Exception:\s*'), '');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final assigned = widget.users.where((u) => _assigneeIds.contains(u.id)).map((u) => u.name).toList();
    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 440),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      widget.existing == null ? 'Add task' : 'Edit task',
                      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                    ),
                  ),
                  IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.close)),
                ],
              ),
              const _FieldLabel('Title'),
              TextField(controller: _title, decoration: const InputDecoration(isDense: true)),
              const _FieldLabel('Details'),
              TextField(controller: _body, minLines: 3, maxLines: 4, decoration: const InputDecoration(isDense: true)),
              const _FieldLabel('Due'),
              InkWell(
                onTap: _pickDue,
                borderRadius: BorderRadius.circular(12),
                child: InputDecorator(
                  decoration: const InputDecoration(
                    isDense: true,
                    suffixIcon: Icon(Icons.calendar_today_outlined, size: 18),
                  ),
                  child: Text(
                    _due == null ? 'dd-mm-yyyy  --:--' : _formatDue(_due!, withTime: true),
                    style: TextStyle(color: _due == null ? AppColors.textGray : AppColors.textMain),
                  ),
                ),
              ),
              const _FieldLabel('Status'),
              _StatusDropdown(
                value: _status,
                expanded: true,
                onChanged: (status) => setState(() => _status = status),
              ),
              const _FieldLabel('Assign to'),
              InkWell(
                onTap: _pickAssignees,
                borderRadius: BorderRadius.circular(12),
                child: InputDecorator(
                  decoration: const InputDecoration(
                    isDense: true,
                    suffixIcon: Icon(Icons.keyboard_arrow_down),
                  ),
                  child: Text(
                    assigned.isEmpty ? 'All users' : assigned.join(', '),
                    style: TextStyle(color: assigned.isEmpty ? AppColors.textGray : AppColors.textMain),
                  ),
                ),
              ),
              const Padding(
                padding: EdgeInsets.only(top: 6),
                child: Text(
                  'Leave this empty to show the task to everyone in the company.',
                  style: TextStyle(fontSize: 12, color: AppColors.textSec),
                ),
              ),
              const _FieldLabel('Contact'),
              TextField(
                controller: _contact,
                decoration: const InputDecoration(
                  isDense: true,
                  hintText: 'Search by name or phone',
                ),
                onChanged: _searchContacts,
              ),
              if (_matches.isNotEmpty)
                Container(
                  margin: const EdgeInsets.only(top: 6),
                  constraints: const BoxConstraints(maxHeight: 160),
                  decoration: BoxDecoration(
                    border: Border.all(color: const Color(0xFFE2E8F0)),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      for (final contact in _matches)
                        ListTile(
                          dense: true,
                          title: Text(ContactListCard.fullName(contact)),
                          onTap: () {
                            final id = '${contact['id'] ?? contact['contact_id'] ?? contact['contactId'] ?? ''}'.trim();
                            final name = ContactListCard.fullName(contact);
                            setState(() {
                              _contactId = id.isEmpty ? null : id;
                              _contactName = name;
                              _contact.text = name;
                              _matches = [];
                            });
                          },
                        ),
                    ],
                  ),
                ),
              if (_error != null) ...[
                const SizedBox(height: 10),
                Text(_error!, style: const TextStyle(color: AppColors.errorRed, fontSize: 13)),
              ],
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(onPressed: _saving ? null : () => Navigator.pop(context), child: const Text('Cancel')),
                  const SizedBox(width: 8),
                  FilledButton(
                    onPressed: _saving ? null : _save,
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.successGreen,
                      foregroundColor: Colors.white,
                      shape: const StadiumBorder(),
                    ),
                    child: Text(_saving ? 'Saving…' : 'Save task'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 6),
      child: Text(text, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
    );
  }
}

class _TaskItem {
  const _TaskItem({
    required this.id,
    required this.title,
    required this.body,
    required this.dueAt,
    required this.status,
    required this.isReminder,
    required this.contactId,
    required this.contactName,
    required this.assigneeIds,
    required this.assigneeNames,
  });

  final String id;
  final String title;
  final String body;
  final DateTime? dueAt;
  final String status;
  final bool isReminder;
  final String? contactId;
  final String contactName;
  final List<int> assigneeIds;
  final List<String> assigneeNames;

  factory _TaskItem.fromMap(Map<String, dynamic> map) {
    final ids = <int>[];
    final rawIds = map['assignee_ids'];
    if (rawIds is List) {
      for (final id in rawIds) {
        final parsed = id is int ? id : int.tryParse('$id');
        if (parsed != null && parsed > 0) ids.add(parsed);
      }
    }
    final names = <String>[];
    final rawNames = map['assignee_names'];
    if (rawNames is List) {
      for (final name in rawNames) {
        final text = '$name'.trim();
        if (text.isNotEmpty) names.add(text);
      }
    }
    return _TaskItem(
      id: '${map['id'] ?? ''}',
      title: '${map['title'] ?? ''}'.trim(),
      body: '${map['body'] ?? ''}'.trim(),
      dueAt: DateTime.tryParse('${map['due_at'] ?? ''}'),
      status: '${map['status'] ?? 'open'}',
      isReminder: map['is_reminder'] == true,
      contactId: '${map['contact_id'] ?? ''}'.trim().isEmpty ? null : '${map['contact_id']}'.trim(),
      contactName: '${map['contact_name'] ?? ''}'.trim(),
      assigneeIds: ids,
      assigneeNames: names,
    );
  }
}

class _Person {
  const _Person({required this.id, required this.name});

  final int id;
  final String name;

  factory _Person.fromMap(Map<String, dynamic> map) {
    final raw = map['id'] ?? map['user_id'] ?? map['userId'];
    return _Person(
      id: raw is int ? raw : int.tryParse('$raw') ?? 0,
      name: '${map['name'] ?? map['full_name'] ?? map['fullName'] ?? 'User'}'.trim(),
    );
  }
}

DateTime _dateOnly(DateTime value) => DateTime(value.year, value.month, value.day);

String _formatDue(DateTime value, {bool withTime = false}) {
  final local = value.toLocal();
  final day = local.day;
  final mod100 = day % 100;
  final suffix = (mod100 >= 11 && mod100 <= 13)
      ? 'th'
      : switch (day % 10) {
          1 => 'st',
          2 => 'nd',
          3 => 'rd',
          _ => 'th',
        };
  final date = '$day$suffix ${DateFormat('MMM').format(local).toUpperCase()} ${local.year}';
  if (!withTime) return date;
  return '$date, ${DateFormat('h:mm a').format(local)}';
}

Color _avatarColor(int index) {
  const colors = [Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFF0F766E)];
  return colors[index % colors.length];
}

String _initials(String name) {
  final parts = name.split(RegExp(r'\s+')).where((p) => p.isNotEmpty).take(2);
  final letters = parts.map((p) => p[0].toUpperCase()).join();
  return letters.isEmpty ? '?' : letters;
}
