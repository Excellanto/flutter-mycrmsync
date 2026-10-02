import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/services/recording_ai_upload.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:mycrmsync/ui/screens/whatsapp_post_call_dialog.dart';
import 'package:mycrmsync/ui/widgets/app_shell.dart';
import 'package:mycrmsync/ui/widgets/recording_player_sheet.dart';
import 'package:url_launcher/url_launcher.dart';

class CallLogsScreen extends ConsumerStatefulWidget {
  const CallLogsScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  ConsumerState<CallLogsScreen> createState() => _CallLogsScreenState();
}

class _CallLogsScreenState extends ConsumerState<CallLogsScreen> {
  final _search = TextEditingController();

  bool _mine = true;
  bool _loading = true;
  bool _uploading = false;
  String? _aiUploadingKey;
  String? _error;
  String _query = '';
  List<_CallLogVm> _rows = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (AppConfig.isAndroid) {
        try {
          await NativeBridges.enqueueNow();
          await NativeBridges.syncOemNow();
        } catch (_) {}
      }
      final apiLogs =
          await ref.read(crmApiProvider).getCallLogs(mine: _mine, limit: 120);
      final localRecordings = AppConfig.isAndroid
          ? await NativeBridges.listLocalWithRecordings(limit: 300)
          : <Map<String, dynamic>>[];
      final waLocal = AppConfig.isAndroid
          ? await NativeBridges.listRecentWhatsAppCalls(limit: 80)
          : <Map<String, dynamic>>[];
      final notedFingerprints = <String>{};
      if (AppConfig.isAndroid) {
        try {
          notedFingerprints.addAll(
            await NativeBridges.listNoteUploadedFingerprints(),
          );
        } catch (_) {}
      }

      final merged = <_CallLogVm>[];
      for (final raw in apiLogs) {
        final row = _CallLogVm.fromApi(raw, localRecordings, showStaff: !_mine);
        merged.add(
          notedFingerprints.contains(row.syncFingerprint)
              ? row.copyWith(noteUploaded: true)
              : row,
        );
      }
      for (final wa in waLocal) {
        final contactId = '${wa['contactId'] ?? ''}'.trim();
        if (contactId.isEmpty) continue;
        final fp = '${wa['syncFingerprint'] ?? ''}';
        if (fp.isNotEmpty && merged.any((m) => m.syncFingerprint == fp)) {
          continue;
        }
        merged.add(_CallLogVm.fromWhatsAppLocal(wa));
      }
      merged.sort((a, b) => b.startedMs.compareTo(a.startedMs));

      if (!mounted) return;
      setState(() {
        _rows = merged;
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

  List<_CallLogVm> get _filtered {
    final q = _query.trim().toLowerCase();
    if (q.isEmpty) return _rows;
    return _rows.where((r) {
      return r.name.toLowerCase().contains(q) ||
          r.phone.toLowerCase().contains(q);
    }).toList();
  }

  Future<void> _uploadAll() async {
    setState(() => _uploading = true);
    try {
      await NativeBridges.enqueueNow();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Upload queued for pending call notes')),
        );
      }
      await _load();
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _call(String phone) async {
    if (phone.isEmpty) return;
    if (AppConfig.isAndroid) {
      try {
        await NativeBridges.placeCall(phone);
        return;
      } catch (_) {}
    }
    await launchUrl(Uri(scheme: 'tel', path: phone));
  }

  Future<void> _openPlayer(_CallLogVm row) async {
    final queue = _filtered
        .where((r) => r.hasRecording)
        .map(
          (r) => RecordingQueueItem(
            uri: r.recordingUri!,
            contactName: r.name,
            phone: r.phone,
            recordedAtIso: r.startedAtIso,
          ),
        )
        .toList();
    if (queue.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No recording available for this call')),
      );
      return;
    }
    final start = queue.indexWhere((q) => q.uri == row.recordingUri);
    await showRecordingPlayerSheet(
      context: context,
      queue: queue,
      startIndex: start < 0 ? 0 : start,
    );
  }

  Future<void> _onCloudTap(_CallLogVm row) async {
    await _openQuickNote(row);
  }

  Future<void> _openQuickNote(_CallLogVm row) async {
    final contactId = row.contactId?.trim() ?? '';
    if (contactId.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No CRM contact linked to this call')),
      );
      return;
    }
    final seed = formatCallStatusNoteBody(
      direction: row.direction,
      durationSec: row.durationSec,
      startedAtIso: row.startedAtIso,
      phone: row.phone,
      isWhatsAppCall: row.isWhatsApp,
    );
    final saved = await showGeneralDialog<bool>(
      context: context,
      barrierDismissible: false,
      barrierColor: Colors.black54,
      pageBuilder: (ctx, _, __) => WhatsAppPostCallDialog(
        payload: {
          'displayName': row.name,
          'direction': row.direction,
          'durationSec': row.durationSec,
          'contactId': contactId,
          'isWhatsAppCall': row.isWhatsApp,
          'noteSeed': seed,
        },
        onClosed: () => Navigator.pop(ctx, false),
        onSaved: () => Navigator.pop(ctx, true),
      ),
    );
    if (saved != true || !mounted) return;
    try {
      await NativeBridges.markCallNoteSaved(
        fingerprint: row.syncFingerprint,
        isWhatsApp: row.isWhatsApp,
      );
    } catch (_) {}
    if (!mounted) return;
    setState(() {
      _rows = _rows
          .map(
            (r) => r.key == row.key ? r.copyWith(noteUploaded: true) : r,
          )
          .toList();
    });
  }

  Future<void> _uploadToAi(_CallLogVm row) async {
    if (!row.hasRecording) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No recording attached to this call')),
      );
      return;
    }
    if (row.contactId == null || row.contactId!.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No CRM contact linked to this call')),
      );
      return;
    }
    setState(() => _aiUploadingKey = row.key);
    try {
      await uploadRecordingToAi(
        api: ref.read(crmApiProvider),
        contactId: row.contactId!,
        recordingUri: row.recordingUri!,
        contactDisplayName: row.name,
        direction: row.direction,
        durationSec: row.durationSec,
        startedAtIso: row.startedAtIso,
        phone: row.phone,
        isWhatsAppCall: row.isWhatsApp,
      );
      if (!mounted) return;
      setState(() {
        _rows = _rows
            .map(
              (r) => r.key == row.key
                  ? r.copyWith(noteUploaded: true, aiUploaded: true)
                  : r,
            )
            .toList();
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Recording uploaded to AI and posted to Timeline'),
        ),
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$e')),
        );
      }
    } finally {
      if (mounted) setState(() => _aiUploadingKey = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _filtered;
    final groups = _groupByDate(filtered);

    return AppShell(
      title: 'Call Logs',
      currentRoute: 'calls',
      onNavigate: widget.onNavigate,
      actions: [
        TextButton(
          onPressed: (!AppConfig.isAndroid || _uploading) ? null : _uploadAll,
          child: Text(
            _uploading ? 'Uploading…' : 'Upload all',
            style: const TextStyle(
              color: AppColors.primaryBlue,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        IconButton(
          tooltip: 'Refresh',
          onPressed: _loading ? null : _load,
          icon: const Icon(Icons.refresh, color: AppColors.primaryBlue),
        ),
      ],
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: Container(
              height: 48,
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
              ),
              child: TextField(
                controller: _search,
                onChanged: (v) => setState(() => _query = v),
                decoration: const InputDecoration(
                  hintText: 'Search name or number...',
                  hintStyle: TextStyle(color: AppColors.textSec, fontSize: 14),
                  prefixIcon: Icon(Icons.search, color: AppColors.textSec),
                  border: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  filled: true,
                  fillColor: Colors.white,
                  contentPadding: EdgeInsets.symmetric(vertical: 12),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
            child: Row(
              children: [
                const Text(
                  'Get log for',
                  style: TextStyle(color: AppColors.textSec, fontSize: 13),
                ),
                const SizedBox(width: 12),
                _ScopeChip(
                  label: 'Me',
                  selected: _mine,
                  onTap: () {
                    if (_mine) return;
                    setState(() => _mine = true);
                    _load();
                  },
                ),
                const SizedBox(width: 8),
                _ScopeChip(
                  label: 'All users',
                  selected: !_mine,
                  onTap: () {
                    if (!_mine) return;
                    setState(() => _mine = false);
                    _load();
                  },
                ),
              ],
            ),
          ),
          if (!AppConfig.isAndroid)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                'CRM history only. Device recordings play on Android when OEM files are matched.',
                style: TextStyle(color: AppColors.textSec, fontSize: 12),
              ),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(_error!, style: const TextStyle(color: AppColors.errorRed)),
            ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : RefreshIndicator(
                    onRefresh: _load,
                    child: filtered.isEmpty
                        ? ListView(
                            children: const [
                              SizedBox(height: 80),
                              Center(
                                child: Column(
                                  children: [
                                    Icon(
                                      Icons.phone_disabled_outlined,
                                      size: 40,
                                      color: AppColors.textSec,
                                    ),
                                    SizedBox(height: 10),
                                    Text(
                                      'No calls found',
                                      style: TextStyle(
                                        color: AppColors.textMain,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                    SizedBox(height: 4),
                                    Text(
                                      'Try adjusting your search or pull to refresh.',
                                      style: TextStyle(
                                        color: AppColors.textSec,
                                        fontSize: 12,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          )
                        : ListView.builder(
                            padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                            itemCount: groups.length,
                            itemBuilder: (context, i) {
                              final g = groups[i];
                              if (g is _DateHeader) {
                                return Padding(
                                  padding: const EdgeInsets.only(top: 12, bottom: 8),
                                  child: Text(
                                    g.label,
                                    style: const TextStyle(
                                      color: AppColors.textSec,
                                      fontSize: 12,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                );
                              }
                              final row = (g as _RowEntry).row;
                              return Padding(
                                padding: const EdgeInsets.only(bottom: 10),
                                child: _CallLogCard(
                                  row: row,
                                  aiUploading: _aiUploadingKey == row.key,
                                  onCall: () => _call(row.phone),
                                  onPlay: () => _openPlayer(row),
                                  onUploadToAi: () => _uploadToAi(row),
                                  onQuickNote: () => _onCloudTap(row),
                                ),
                              );
                            },
                          ),
                  ),
          ),
        ],
      ),
    );
  }

  List<Object> _groupByDate(List<_CallLogVm> rows) {
    final out = <Object>[];
    String? lastLabel;
    for (final row in rows) {
      final label = row.dateGroupLabel;
      if (label != lastLabel) {
        out.add(_DateHeader(label));
        lastLabel = label;
      }
      out.add(_RowEntry(row));
    }
    return out;
  }
}

class _DateHeader {
  _DateHeader(this.label);
  final String label;
}

class _RowEntry {
  _RowEntry(this.row);
  final _CallLogVm row;
}

class _ScopeChip extends StatelessWidget {
  const _ScopeChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AppColors.lightIndigo : Colors.transparent,
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
              color: selected ? AppColors.lightIndigo : const Color(0xFFCBD5E1),
            ),
          ),
          child: Text(
            label,
            style: TextStyle(
              color: selected ? AppColors.darkBlue : AppColors.textSec,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
              fontSize: 13,
            ),
          ),
        ),
      ),
    );
  }
}

class _CallLogCard extends StatelessWidget {
  const _CallLogCard({
    required this.row,
    required this.aiUploading,
    required this.onCall,
    required this.onPlay,
    required this.onUploadToAi,
    this.onQuickNote,
  });

  final _CallLogVm row;
  final bool aiUploading;
  final VoidCallback onCall;
  final VoidCallback onPlay;
  final VoidCallback onUploadToAi;
  final VoidCallback? onQuickNote;

  @override
  Widget build(BuildContext context) {
    final accent = row.isMissed
        ? AppColors.errorRed
        : row.isIncoming
            ? AppColors.successGreen
            : AppColors.primaryBlue;
    final bg = accent.withValues(alpha: 0.12);
    final icon = row.isMissed
        ? Icons.call_missed
        : row.isIncoming
            ? Icons.call_received
            : Icons.call_made;

    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
      elevation: 0,
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: const Color(0xFFE2E8F0)),
        ),
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(color: bg, shape: BoxShape.circle),
                  child: Icon(icon, color: accent, size: 22),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        row.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: row.isMissed
                              ? AppColors.errorRed
                              : AppColors.textMain,
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                      if (row.phone.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(
                          row.phone,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: AppColors.textSec,
                            fontSize: 12,
                          ),
                        ),
                      ],
                      if (row.isWhatsApp) ...[
                        const SizedBox(height: 3),
                        const Row(
                          children: [
                            Icon(Icons.chat, size: 12, color: Color(0xFF25D366)),
                            SizedBox(width: 4),
                            Text(
                              'WhatsApp',
                              style: TextStyle(
                                color: Color(0xFF25D366),
                                fontSize: 10,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                      ],
                      if (row.staffName != null && row.staffName!.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(
                          row.staffName!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: AppColors.primaryBlue,
                            fontSize: 10,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _ActionCircle(
                          icon: Icons.phone,
                          tooltip: 'Call',
                          onTap: onCall,
                        ),
                        if (row.hasRecording) ...[
                          const SizedBox(width: 6),
                          _ActionCircle(
                            icon: Icons.play_arrow,
                            tooltip: 'Play recording',
                            onTap: onPlay,
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      row.durationLabel,
                      style: const TextStyle(
                        color: AppColors.textMain,
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: bg,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    row.directionChip,
                    style: TextStyle(
                      color: accent,
                      fontSize: 9,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                const Icon(
                  Icons.access_time,
                  size: 14,
                  color: AppColors.textSec,
                ),
                const SizedBox(width: 3),
                Text(
                  row.timeLabel,
                  style: const TextStyle(
                    color: AppColors.textSec,
                    fontSize: 10,
                  ),
                ),
                const SizedBox(width: 8),
                const Icon(
                  Icons.calendar_today,
                  size: 12,
                  color: AppColors.textSec,
                ),
                const SizedBox(width: 3),
                Text(
                  row.shortDateLabel,
                  style: const TextStyle(
                    color: AppColors.textSec,
                    fontSize: 10,
                  ),
                ),
                const Spacer(),
                IconButton(
                  onPressed: onQuickNote,
                  tooltip: 'Add note',
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                  icon: const Icon(
                    Icons.cloud_outlined,
                    size: 18,
                    color: AppColors.textSec,
                  ),
                ),
              ],
            ),
            if (row.hasRecording) ...[
              const SizedBox(height: 10),
              SizedBox(
                height: 42,
                child: FilledButton.icon(
                  onPressed: aiUploading ? null : onUploadToAi,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.primaryBlue,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  icon: aiUploading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.auto_awesome, size: 18),
                  label: Text(
                    aiUploading ? 'Uploading to AI…' : 'Upload to AI',
                    style: const TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 13,
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ActionCircle extends StatelessWidget {
  const _ActionCircle({
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: Container(
          width: 34,
          height: 34,
          decoration: const BoxDecoration(
            color: AppColors.lightIndigo,
            shape: BoxShape.circle,
          ),
          child: Icon(
            icon,
            size: 18,
            color: AppColors.primaryBlue,
          ),
        ),
      ),
    );
  }
}

class _CallLogVm {
  _CallLogVm({
    required this.key,
    required this.name,
    required this.phone,
    required this.direction,
    required this.durationSec,
    required this.startedMs,
    required this.startedAtIso,
    required this.isWhatsApp,
    required this.synced,
    required this.noteUploaded,
    required this.recordingUri,
    required this.syncFingerprint,
    this.contactId,
    this.staffName,
    this.aiUploaded = false,
  });

  final String key;
  final String name;
  final String phone;
  final String direction;
  final int durationSec;
  final int startedMs;
  final String startedAtIso;
  final bool isWhatsApp;
  final bool synced;
  final bool noteUploaded;
  final String? recordingUri;
  final String syncFingerprint;
  final String? contactId;
  final String? staffName;
  final bool aiUploaded;

  _CallLogVm copyWith({
    bool? noteUploaded,
    bool? aiUploaded,
    String? recordingUri,
  }) {
    return _CallLogVm(
      key: key,
      name: name,
      phone: phone,
      direction: direction,
      durationSec: durationSec,
      startedMs: startedMs,
      startedAtIso: startedAtIso,
      isWhatsApp: isWhatsApp,
      synced: synced,
      noteUploaded: noteUploaded ?? this.noteUploaded,
      recordingUri: recordingUri ?? this.recordingUri,
      syncFingerprint: syncFingerprint,
      contactId: contactId,
      staffName: staffName,
      aiUploaded: aiUploaded ?? this.aiUploaded,
    );
  }

  bool get isMissed => direction.toUpperCase().contains('MISS');

  /// "OUTGOING" also contains "IN", so outgoing has to be tested first.
  bool get isOutgoing => direction.toUpperCase().contains('OUT');
  bool get isIncoming => !isMissed && !isOutgoing && direction.toUpperCase().contains('IN');
  bool get hasRecording => recordingUri != null && recordingUri!.isNotEmpty;

  String get directionChip {
    final d = direction.toUpperCase();
    if (d.contains('MISS')) return 'MISSED';
    if (d.contains('OUT')) return 'OUTGOING';
    if (d.contains('IN')) return 'INCOMING';
    return d.isEmpty ? 'UNKNOWN' : d;
  }

  String get durationLabel {
    if (isMissed && durationSec <= 0) return '—';
    if (durationSec <= 0) return '0s';
    final m = durationSec ~/ 60;
    final s = durationSec % 60;
    if (m > 0) return '${m}m ${s}s';
    return '${s}s';
  }

  String get timeLabel {
    if (startedMs <= 0) return '—';
    return DateFormat('h:mm a').format(DateTime.fromMillisecondsSinceEpoch(startedMs));
  }

  String get shortDateLabel {
    if (startedMs <= 0) return '—';
    return DateFormat('MMM d').format(DateTime.fromMillisecondsSinceEpoch(startedMs));
  }

  String get dateGroupLabel {
    if (startedMs <= 0) return 'Unknown';
    return DateFormat('MMMM d').format(DateTime.fromMillisecondsSinceEpoch(startedMs));
  }

  factory _CallLogVm.fromApi(
    Map<String, dynamic> raw,
    List<Map<String, dynamic>> localRecordings, {
    bool showStaff = false,
  }) {
    final phone =
        '${raw['phone_e164'] ?? raw['phoneE164'] ?? raw['phone_raw'] ?? raw['phoneRaw'] ?? raw['phone'] ?? ''}'
            .trim();
    final startedIso = '${raw['started_at'] ?? raw['startedAt'] ?? ''}'.trim();
    final startedMs = _parseMs(startedIso);
    final direction = '${raw['direction'] ?? ''}'.trim();
    final duration = _asInt(raw['duration_sec'] ?? raw['durationSec']);
    final fp = '${raw['sync_fingerprint'] ?? raw['syncFingerprint'] ?? ''}'.trim();
    final name =
        '${raw['contact_name'] ?? raw['contactName'] ?? raw['user_name'] ?? phone}'
            .trim();
    final apiRecording =
        '${raw['recording_url_long'] ?? raw['recordingUrlLong'] ?? raw['recording_url'] ?? raw['recordingUrl'] ?? ''}'
            .trim();

    final recording = apiRecording.isEmpty
        ? _matchLocalRecording(
            localRecordings,
            phone: phone,
            startedMs: startedMs,
            durationSec: duration,
          )
        : apiRecording;

    final isWa = fp.startsWith('whatsapp|') ||
        '${raw['source'] ?? ''}'.toLowerCase().contains('whatsapp');
    final staff = showStaff
        ? '${raw['staff_user_name'] ?? raw['staffUserName'] ?? raw['user_name'] ?? raw['userName'] ?? ''}'
            .trim()
        : '';
    final contactId =
        '${raw['contact_id'] ?? raw['contactId'] ?? raw['crm_contact_id'] ?? raw['crmContactId'] ?? ''}'
            .trim();

    return _CallLogVm(
      key: fp.isNotEmpty ? fp : 'api-$phone-$startedMs',
      name: name.isEmpty ? 'Unknown' : name,
      phone: phone,
      direction: direction,
      durationSec: duration,
      startedMs: startedMs,
      startedAtIso: startedIso,
      isWhatsApp: isWa,
      synced: true,
      noteUploaded: raw['ghl_note_id'] != null || raw['note_id'] != null,
      recordingUri: recording,
      syncFingerprint: fp,
      contactId: contactId.isEmpty ? null : contactId,
      staffName: staff.isEmpty ? null : staff,
    );
  }

  factory _CallLogVm.fromWhatsAppLocal(Map<String, dynamic> raw) {
    final startedIso = '${raw['startedAtIso'] ?? ''}'.trim();
    final startedMs = _parseMs(startedIso);
    final name = '${raw['displayName'] ?? 'WhatsApp'}'.trim();
    final fp = '${raw['syncFingerprint'] ?? ''}'.trim();
    return _CallLogVm(
      key: fp.isNotEmpty ? fp : 'wa-$startedMs',
      name: name.isEmpty ? 'WhatsApp' : name,
      phone: '',
      direction: '${raw['direction'] ?? 'OUTGOING'}',
      durationSec: _asInt(raw['durationSec']),
      startedMs: startedMs,
      startedAtIso: startedIso,
      isWhatsApp: true,
      synced: '${raw['uploadStatus'] ?? ''}'.toLowerCase() == 'synced',
      noteUploaded: raw['noteUploaded'] == true,
      recordingUri: null,
      syncFingerprint: fp,
      contactId: '${raw['contactId'] ?? ''}'.trim().isEmpty
          ? null
          : '${raw['contactId']}'.trim(),
    );
  }

  static String? _matchLocalRecording(
    List<Map<String, dynamic>> locals, {
    required String phone,
    required int startedMs,
    required int durationSec,
  }) {
    if (locals.isEmpty || startedMs <= 0) return null;
    final phoneDigits = phone.replaceAll(RegExp(r'\D'), '');
    final tail = phoneDigits.length >= 8
        ? phoneDigits.substring(phoneDigits.length - 8)
        : phoneDigits;
    String? bestUri;
    var bestScore = -1.0;
    for (final loc in locals) {
      final uri = '${loc['recordingUri'] ?? ''}'.trim();
      if (uri.isEmpty) continue;
      final locPhone =
          '${loc['phoneNumber'] ?? ''}'.replaceAll(RegExp(r'\D'), '');
      final locTail = locPhone.length >= 8
          ? locPhone.substring(locPhone.length - 8)
          : locPhone;
      if (tail.isNotEmpty && locTail.isNotEmpty && tail != locTail) continue;
      final dateMs = _asInt(loc['dateMs']);
      final delta = (dateMs - startedMs).abs();
      if (delta > 15 * 60 * 1000) continue;
      final locDur = _asInt(loc['durationSec']);
      final durDelta = (locDur - durationSec).abs();
      final score = 1000.0 - (delta / 1000.0) - durDelta;
      if (score > bestScore) {
        bestScore = score;
        bestUri = uri;
      }
    }
    return bestUri;
  }

  static int _parseMs(String iso) {
    if (iso.isEmpty) return 0;
    try {
      return DateTime.parse(iso).millisecondsSinceEpoch;
    } catch (_) {
      return 0;
    }
  }

  static int _asInt(dynamic v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    return int.tryParse('$v') ?? 0;
  }
}
