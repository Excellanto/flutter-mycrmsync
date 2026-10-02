import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/services/call_note_format.dart';
import 'package:mycrmsync/services/timeline_note_parser.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:mycrmsync/ui/widgets/recording_player_sheet.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:record/record.dart';
import 'package:url_launcher/url_launcher.dart';

class _NoteAttachmentPreview {
  const _NoteAttachmentPreview({
    required this.voiceNoteId,
    required this.name,
    required this.url,
    required this.fileType,
    this.localPath,
    this.isPending = false,
  });

  final String voiceNoteId;
  final String name;
  final String url;
  final String fileType;
  final String? localPath;
  final bool isPending;

  bool get isImage {
    final t = fileType.toLowerCase();
    return t == 'image' ||
        t == 'jpg' ||
        t == 'jpeg' ||
        t == 'png' ||
        t == 'gif' ||
        t == 'webp' ||
        t == 'bmp';
  }

  bool get isAudio {
    final t = fileType.toLowerCase();
    return t == 'm4a' ||
        t == 'mp3' ||
        t == 'wav' ||
        t == 'aac' ||
        t == 'audio' ||
        t.contains('audio');
  }
}

class _ProcessedVoicePreview {
  const _ProcessedVoicePreview({
    required this.voiceNoteId,
    required this.summary,
    required this.transcription,
    this.fileName,
  });

  final String voiceNoteId;
  final String summary;
  final String transcription;
  final String? fileName;
}

class ContactNotesScreen extends ConsumerStatefulWidget {
  const ContactNotesScreen({
    super.key,
    required this.contactId,
    required this.contactName,
    required this.onBack,
  });

  final String contactId;
  final String contactName;
  final VoidCallback onBack;

  @override
  ConsumerState<ContactNotesScreen> createState() => _ContactNotesScreenState();
}

class _ContactNotesScreenState extends ConsumerState<ContactNotesScreen> {
  final _noteController = TextEditingController();
  final _imagePicker = ImagePicker();
  final _audioRecorder = AudioRecorder();

  bool _loading = true;
  bool _saving = false;
  bool _uploadingAttachment = false;
  bool _recording = false;
  bool _processingVoice = false;
  bool _setReminder = false;
  DateTime? _reminderAt;
  String? _error;
  String? _recordingPath;
  List<Map<String, dynamic>> _notes = [];
  final List<_NoteAttachmentPreview> _attachments = [];
  _ProcessedVoicePreview? _processedVoice;
  String _filter = 'All Notes';

  static const _filterOptions = [
    'All Notes',
    'Call logs',
    'WhatsApp',
    'Manual',
  ];
  static const _maxFileBytes = 50 * 1024 * 1024;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _noteController.dispose();
    _audioRecorder.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final notes =
          await ref.read(crmApiProvider).getContactNotes(widget.contactId);
      notes.sort((a, b) {
        final aMs = _noteMs(a);
        final bMs = _noteMs(b);
        return bMs.compareTo(aMs);
      });
      if (!mounted) return;
      setState(() {
        _notes = notes;
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

  int _noteMs(Map<String, dynamic> n) {
    final raw =
        '${n['created_at'] ?? n['createdAt'] ?? n['dateAdded'] ?? n['date_added'] ?? ''}'
            .trim();
    if (raw.isEmpty) return 0;
    try {
      return DateTime.parse(raw).millisecondsSinceEpoch;
    } catch (_) {
      return 0;
    }
  }

  List<Map<String, dynamic>> get _filteredNotes {
    return _notes.where((n) {
      final parsed = TimelineNoteParser.parse(n);
      final text =
          '${parsed.introText} ${parsed.summary ?? ''} ${parsed.transcription ?? ''}';
      switch (_filter) {
        case 'Call logs':
          return isDeviceCallNote(text);
        case 'WhatsApp':
          return isWhatsAppCallNote(text);
        case 'Manual':
          return !isDeviceCallNote(text) && !isWhatsAppCallNote(text);
        default:
          return true;
      }
    }).toList();
  }

  String _addedBy(Map<String, dynamic> n) {
    final user = n['user'] ?? n['created_by'] ?? n['createdBy'] ?? n['author'];
    if (user is Map) {
      final name = '${user['name'] ?? user['full_name'] ?? ''}'.trim();
      if (name.isNotEmpty) return name;
    }
    final direct =
        '${n['user_name'] ?? n['created_by_name'] ?? n['added_by'] ?? ''}'
            .trim();
    return direct.isEmpty ? 'System' : direct;
  }

  String _formatDate(Map<String, dynamic> n) {
    final raw =
        '${n['created_at'] ?? n['createdAt'] ?? n['dateAdded'] ?? n['date_added'] ?? ''}'
            .trim();
    if (raw.isEmpty) return 'Unknown date';
    try {
      final dt = DateTime.parse(raw).toLocal();
      return DateFormat('MMM d, h:mm a').format(dt);
    } catch (_) {
      return raw;
    }
  }

  String? _rawDateIso(Map<String, dynamic> n) {
    final raw =
        '${n['created_at'] ?? n['createdAt'] ?? n['dateAdded'] ?? n['date_added'] ?? ''}'
            .trim();
    return raw.isEmpty ? null : raw;
  }

  List<String> get _voiceNoteIds => _attachments
      .where((a) => !a.isPending && a.voiceNoteId.isNotEmpty)
      .map((a) => a.voiceNoteId)
      .toList();

  Future<void> _saveNote() async {
    final text = _noteController.text.trim();
    // Pending local recording (not AI-processed) — attach as raw audio first.
    if (_recordingPath != null &&
        !_recording &&
        File(_recordingPath!).existsSync()) {
      final ok = await _attachRecordingWithoutAi(showSnack: false);
      if (!ok) return;
    }
    final ids = _voiceNoteIds;
    if (text.isEmpty && ids.isEmpty) return;
    if (_attachments.any((a) => a.isPending)) {
      _snack('Please wait for attachments to finish uploading.');
      return;
    }
    if (_setReminder && _reminderAt == null) {
      _snack('Pick a date and time for the reminder.');
      return;
    }

    setState(() => _saving = true);
    try {
      final api = ref.read(crmApiProvider);
      final reminderAt = _setReminder ? _reminderAt : null;
      String? reminderError;
      if (ids.isNotEmpty) {
        // Images, files, and voice notes all share voice_note_id — one batch posts them together.
        final saved = await api.processMediaBatch(
          contactId: widget.contactId,
          voiceNoteIds: ids,
          existingNoteText: text.isEmpty ? null : text,
        );
        if (reminderAt != null) {
          final noteId = '${saved['crm_note_id'] ?? ''}'.trim();
          final excerpt = text.isNotEmpty
              ? text
              : (_processedVoice?.summary ?? '').trim();
          try {
            await api.createContactReminder(
              contactId: widget.contactId,
              reminderAt: reminderAt,
              noteId: noteId.isEmpty ? null : noteId,
              body: excerpt.isEmpty ? null : excerpt,
              contactName: widget.contactName,
            );
          } catch (e) {
            reminderError = '$e';
          }
        }
      } else {
        await api.createContactNote(
          contactId: widget.contactId,
          body: text,
          reminderAt: reminderAt,
          contactName: widget.contactName,
        );
      }
      _noteController.clear();
      _attachments.clear();
      _processedVoice = null;
      _setReminder = false;
      _reminderAt = null;
      await _load();
      if (reminderError != null) {
        _snack('Note saved. $reminderError');
      }
    } catch (e) {
      _snack('$e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _onSetReminder(bool enabled) async {
    setState(() => _setReminder = enabled);
    if (enabled && _reminderAt == null) {
      await _pickReminderDateTime();
    }
  }

  Future<void> _pickReminderDateTime() async {
    final now = DateTime.now();
    final initial = _reminderAt ?? now.add(const Duration(hours: 1));
    final date = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(now.year, now.month, now.day),
      lastDate: DateTime(now.year + 5),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(initial),
    );
    if (time == null || !mounted) return;
    setState(() {
      _reminderAt = DateTime(
        date.year,
        date.month,
        date.day,
        time.hour,
        time.minute,
      );
    });
  }

  String? _reminderLabel(Map<String, dynamic> n) {
    final reminder = n['reminder'];
    if (reminder is! Map) return null;
    final raw = '${reminder['remind_at'] ?? ''}'.trim();
    if (raw.isEmpty) return null;
    try {
      final dt = DateTime.parse(raw).toLocal();
      final prefix = dt.isBefore(DateTime.now()) ? 'Overdue reminder' : 'Reminder';
      return '$prefix · ${DateFormat('MMM d, h:mm a').format(dt)}';
    } catch (_) {
      return 'Reminder · $raw';
    }
  }

  /// Uploads the local recording via `contact-files/process` (no transcription/summary).
  Future<bool> _attachRecordingWithoutAi({bool showSnack = true}) async {
    final path = _recordingPath;
    if (path == null || !File(path).existsSync()) {
      if (showSnack) _snack('No recording to upload');
      return false;
    }
    final name = path.split(RegExp(r'[\\/]')).last;
    final ok = await _uploadPickedFile(
      path: path,
      name: name.isEmpty
          ? 'voice_${DateTime.now().millisecondsSinceEpoch}.m4a'
          : name,
      mimeHint: 'audio/mp4',
    );
    if (!ok) return false;
    if (!mounted) return false;
    setState(() {
      _recordingPath = null;
      _processedVoice = null;
    });
    try {
      await File(path).delete();
    } catch (_) {}
    if (showSnack) {
      _snack('Recording attached — tap Save Note to post to Timeline');
    }
    return true;
  }

  Future<bool> _uploadPickedFile({
    required String path,
    required String name,
    required String mimeHint,
  }) async {
    final file = File(path);
    if (!file.existsSync()) {
      _snack('Could not read file');
      return false;
    }
    if (file.lengthSync() > _maxFileBytes) {
      _snack('File exceeds 50 MB limit');
      return false;
    }

    final isImage = mimeHint.startsWith('image/') ||
        RegExp(r'\.(jpg|jpeg|png|gif|webp|bmp)$', caseSensitive: false)
            .hasMatch(name);
    final isAudio = mimeHint.startsWith('audio/') ||
        RegExp(r'\.(m4a|mp3|wav|aac|ogg|amr)$', caseSensitive: false)
            .hasMatch(name);
    final pendingId = 'pending_${DateTime.now().microsecondsSinceEpoch}';

    setState(() {
      _uploadingAttachment = true;
      if (isImage) {
        _attachments.add(
          _NoteAttachmentPreview(
            voiceNoteId: pendingId,
            name: name,
            url: path,
            fileType: 'image',
            localPath: path,
            isPending: true,
          ),
        );
      } else if (isAudio) {
        _attachments.add(
          _NoteAttachmentPreview(
            voiceNoteId: pendingId,
            name: name,
            url: path,
            fileType: 'm4a',
            localPath: path,
            isPending: true,
          ),
        );
      }
    });

    try {
      final data = await ref.read(crmApiProvider).processContactFile(
            contactId: widget.contactId,
            filePath: path,
            fileName: name,
            mimeType: mimeHint,
          );
      final voiceNoteId =
          '${data['voice_note_id'] ?? data['voiceNoteId'] ?? ''}'.trim();
      if (voiceNoteId.isEmpty) {
        throw Exception('Upload succeeded but no voice_note_id returned');
      }
      final remoteUrl = _previewUrlFromProcess(data);
      final fileType =
          '${data['file_type'] ?? data['fileType'] ?? (isImage ? 'image' : (isAudio ? 'm4a' : _extOf(name)))}'
              .trim();
      final fileName =
          '${data['file_name'] ?? data['fileName'] ?? name}'.trim();

      if (!mounted) return false;
      setState(() {
        final idx = _attachments.indexWhere((a) => a.voiceNoteId == pendingId);
        final preview = _NoteAttachmentPreview(
          voiceNoteId: voiceNoteId,
          name: fileName.isEmpty ? name : fileName,
          url: remoteUrl.isNotEmpty ? remoteUrl : path,
          fileType: fileType.isEmpty
              ? (isImage ? 'image' : (isAudio ? 'm4a' : 'file'))
              : fileType,
          localPath: isImage || isAudio ? path : null,
        );
        if (idx >= 0) {
          _attachments[idx] = preview;
        } else {
          _attachments.add(preview);
        }
      });
      return true;
    } catch (e) {
      if (mounted) {
        setState(() {
          _attachments.removeWhere((a) => a.voiceNoteId == pendingId);
        });
      }
      _snack('$e');
      return false;
    } finally {
      if (mounted) setState(() => _uploadingAttachment = false);
    }
  }

  String _previewUrlFromProcess(Map<String, dynamic> data) {
    for (final key in [
      'fileslongurl',
      'file_long_url',
      'fileLongUrl',
      'fileshorturl',
      'file_short_url',
      'fileShortUrl',
      'file_url',
      'fileUrl',
      'recording_url_long',
      'recordingUrlLong',
      'recording_url',
      'recordingUrl',
    ]) {
      final v = '${data[key] ?? ''}'.trim();
      if (v.isNotEmpty) return v;
    }
    return '';
  }

  String _extOf(String name) {
    final i = name.lastIndexOf('.');
    if (i < 0) return 'file';
    return name.substring(i + 1).toLowerCase();
  }

  Future<void> _pickImage() async {
    final file = await _imagePicker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 92,
      requestFullMetadata: false,
    );
    if (file == null) return;
    final name = file.name.trim().isEmpty
        ? 'image_${DateTime.now().millisecondsSinceEpoch}.jpg'
        : file.name;
    final mime = (file.mimeType ?? '').trim().isNotEmpty
        ? file.mimeType!
        : _mimeForFileName(name, fallbackImage: true);
    await _uploadPickedFile(
      path: file.path,
      name: name,
      mimeHint: mime,
    );
  }

  Future<void> _pickFile() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['pdf', 'doc', 'docx', 'jpg', 'jpeg', 'png'],
      withData: false,
    );
    if (result == null || result.files.isEmpty) return;
    final f = result.files.first;
    final path = f.path;
    if (path == null) {
      _snack('Could not access selected file');
      return;
    }
    await _uploadPickedFile(
      path: path,
      name: f.name,
      mimeHint: _mimeForFileName(f.name),
    );
  }

  String _mimeForFileName(String name, {bool fallbackImage = false}) {
    final ext = _extOf(name);
    return switch (ext) {
      'jpg' || 'jpeg' => 'image/jpeg',
      'png' => 'image/png',
      'gif' => 'image/gif',
      'webp' => 'image/webp',
      'bmp' => 'image/bmp',
      'pdf' => 'application/pdf',
      'doc' => 'application/msword',
      'docx' =>
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      _ => fallbackImage ? 'image/jpeg' : 'application/octet-stream',
    };
  }

  Future<void> _toggleVoiceRecord() async {
    if (_recording) {
      final path = await _audioRecorder.stop();
      setState(() {
        _recording = false;
        _recordingPath = path ?? _recordingPath;
      });
      return;
    }
    final mic = await Permission.microphone.request();
    if (!mic.isGranted) {
      _snack('Microphone permission is required for voice notes.');
      return;
    }
    final dir = await getTemporaryDirectory();
    final out =
        '${dir.path}/voice_note_${DateTime.now().millisecondsSinceEpoch}.m4a';
    await _audioRecorder.start(
      const RecordConfig(encoder: AudioEncoder.aacLc),
      path: out,
    );
    setState(() {
      _recording = true;
      _recordingPath = out;
    });
  }

  Future<void> _uploadVoiceToAi() async {
    final path = _recordingPath;
    if (path == null || !File(path).existsSync()) {
      _snack('No recording to upload');
      return;
    }
    setState(() => _processingVoice = true);
    try {
      // Only send the user's typed note — do NOT embed summary/transcription here.
      // The server attaches those once when media/process-batch runs with this voice_note_id.
      final typed = _noteController.text.trim();
      final data = await ref.read(crmApiProvider).processVoiceNote(
            contactId: widget.contactId,
            audioPath: path,
            existingNoteText: typed.isEmpty ? null : typed,
          );
      final voiceNoteId =
          '${data['voice_note_id'] ?? data['voiceNoteId'] ?? ''}'.trim();
      if (voiceNoteId.isEmpty) {
        throw Exception('Voice processing returned no id');
      }
      final url = _previewUrlFromProcess(data);
      final name =
          '${data['file_name'] ?? data['fileName'] ?? 'Voice note'}'.trim();
      final summary = '${data['summary'] ?? ''}'.trim();
      final transcription = '${data['transcription'] ?? ''}'.trim();
      if (!mounted) return;
      setState(() {
        _attachments.removeWhere((a) => a.isAudio);
        _attachments.add(
          _NoteAttachmentPreview(
            voiceNoteId: voiceNoteId,
            name: name.isEmpty ? 'Voice note' : name,
            url: url,
            fileType: 'm4a',
          ),
        );
        _processedVoice = _ProcessedVoicePreview(
          voiceNoteId: voiceNoteId,
          summary: summary,
          transcription: transcription,
          fileName: name.isEmpty ? null : name,
        );
        _recordingPath = null;
      });
      try {
        await File(path).delete();
      } catch (_) {}
      final extras = _attachments.where((a) => !a.isAudio).length;
      _snack(
        extras > 0
            ? 'Voice note ready — Save Note will post it with your $extras other attachment(s)'
            : 'Voice note ready — tap Save Note to post to Timeline',
      );
    } catch (e) {
      _snack('$e');
    } finally {
      if (mounted) setState(() => _processingVoice = false);
    }
  }

  void _removeAttachment(int index) {
    setState(() {
      if (index < 0 || index >= _attachments.length) return;
      final removed = _attachments.removeAt(index);
      if (_processedVoice?.voiceNoteId == removed.voiceNoteId) {
        _processedVoice = null;
      }
    });
  }

  void _clearProcessedVoice() {
    setState(() {
      final id = _processedVoice?.voiceNoteId;
      _processedVoice = null;
      if (id != null) {
        _attachments.removeWhere((a) => a.voiceNoteId == id);
      }
    });
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final notes = _filteredNotes;
    final busy = _saving || _uploadingAttachment || _processingVoice;

    return Scaffold(
      backgroundColor: AppColors.lightBg,
      appBar: AppBar(
        backgroundColor: AppColors.lightBg,
        surfaceTintColor: Colors.transparent,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.textMain),
          onPressed: widget.onBack,
        ),
        title: Text(
          widget.contactName,
          style: const TextStyle(
            fontWeight: FontWeight.bold,
            color: AppColors.textMain,
          ),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh, color: AppColors.textMain),
            onPressed: _loading ? null : _load,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                children: [
                  _ComposerCard(
                    controller: _noteController,
                    saving: busy,
                    uploading: _uploadingAttachment,
                    recording: _recording,
                    processingVoice: _processingVoice,
                    hasRecordedAudio: _recordingPath != null && !_recording,
                    attachments: _attachments,
                    processedVoice: _processedVoice,
                    onRemoveAttachment: _removeAttachment,
                    onClearProcessedVoice: _clearProcessedVoice,
                    onAttachFile: _pickFile,
                    onAttachImage: _pickImage,
                    onToggleVoice: _toggleVoiceRecord,
                    onAttachRecording: () => _attachRecordingWithoutAi(),
                    onUploadVoice: _uploadVoiceToAi,
                    onDiscardVoice: () {
                      setState(() {
                        _recordingPath = null;
                        _recording = false;
                      });
                    },
                    setReminder: _setReminder,
                    reminderAt: _reminderAt,
                    onSetReminderChanged: _onSetReminder,
                    onPickReminder: _pickReminderDateTime,
                    onSave: _saveNote,
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: 12),
                    Text(_error!, style: const TextStyle(color: AppColors.errorRed)),
                  ],
                  const SizedBox(height: 20),
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          'Timeline',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.bold,
                            color: AppColors.textMain,
                          ),
                        ),
                      ),
                      PopupMenuButton<String>(
                        onSelected: (v) => setState(() => _filter = v),
                        itemBuilder: (context) => _filterOptions
                            .map(
                              (o) => PopupMenuItem(value: o, child: Text(o)),
                            )
                            .toList(),
                        child: Text(
                          'Filter: $_filter ▾',
                          style: const TextStyle(
                            color: AppColors.textSec,
                            fontSize: 14,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  if (notes.isEmpty)
                    const Padding(
                      padding: EdgeInsets.only(top: 40),
                      child: Center(
                        child: Text(
                          'No notes yet',
                          style: TextStyle(color: AppColors.textSec),
                        ),
                      ),
                    )
                  else
                    ...List.generate(notes.length, (i) {
                      final n = notes[i];
                      return _TimelineEntry(
                        dateLabel: _formatDate(n),
                        addedBy: _addedBy(n),
                        contactName: widget.contactName,
                        recordedAtIso: _rawDateIso(n),
                        reminderLabel: _reminderLabel(n),
                        parsed: TimelineNoteParser.parse(n),
                        isLast: i == notes.length - 1,
                      );
                    }),
                ],
              ),
            ),
    );
  }
}

class _ComposerCard extends StatefulWidget {
  const _ComposerCard({
    required this.controller,
    required this.saving,
    required this.uploading,
    required this.recording,
    required this.processingVoice,
    required this.hasRecordedAudio,
    required this.attachments,
    this.processedVoice,
    required this.onRemoveAttachment,
    required this.onClearProcessedVoice,
    required this.onAttachFile,
    required this.onAttachImage,
    required this.onToggleVoice,
    required this.onAttachRecording,
    required this.onUploadVoice,
    required this.onDiscardVoice,
    required this.setReminder,
    required this.reminderAt,
    required this.onSetReminderChanged,
    required this.onPickReminder,
    required this.onSave,
  });

  final TextEditingController controller;
  final bool saving;
  final bool uploading;
  final bool recording;
  final bool processingVoice;
  final bool hasRecordedAudio;
  final List<_NoteAttachmentPreview> attachments;
  final _ProcessedVoicePreview? processedVoice;
  final void Function(int index) onRemoveAttachment;
  final VoidCallback onClearProcessedVoice;
  final VoidCallback onAttachFile;
  final VoidCallback onAttachImage;
  final VoidCallback onToggleVoice;
  final VoidCallback onAttachRecording;
  final VoidCallback onUploadVoice;
  final VoidCallback onDiscardVoice;
  final bool setReminder;
  final DateTime? reminderAt;
  final ValueChanged<bool> onSetReminderChanged;
  final VoidCallback onPickReminder;
  final VoidCallback onSave;

  @override
  State<_ComposerCard> createState() => _ComposerCardState();
}

class _ComposerCardState extends State<_ComposerCard> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onText);
  }

  @override
  void didUpdateWidget(covariant _ComposerCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onText);
      widget.controller.addListener(_onText);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onText);
    super.dispose();
  }

  void _onText() => setState(() {});

  @override
  Widget build(BuildContext context) {
    final canSave = !widget.saving &&
        !widget.uploading &&
        !widget.processingVoice &&
        (widget.controller.text.trim().isNotEmpty ||
            widget.attachments.any((a) => !a.isPending) ||
            widget.hasRecordedAudio);
    return Material(
      color: Colors.white,
      elevation: 2,
      shadowColor: Colors.black12,
      borderRadius: BorderRadius.circular(16),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const CircleAvatar(
                  radius: 24,
                  backgroundColor: AppColors.lightBg,
                  child: Icon(Icons.person, color: AppColors.textSec, size: 28),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: widget.controller,
                    minLines: 3,
                    maxLines: 6,
                    enabled: !widget.saving,
                    decoration: InputDecoration(
                      hintText: 'Type a note or record a voice update...',
                      hintStyle: const TextStyle(
                        color: AppColors.textSec,
                        fontSize: 15,
                      ),
                      filled: true,
                      fillColor: AppColors.inputBg,
                      contentPadding: const EdgeInsets.all(14),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                ),
              ],
            ),
            if (widget.uploading) ...[
              const SizedBox(height: 10),
              const Row(
                children: [
                  SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  SizedBox(width: 10),
                  Text(
                    'Uploading attachment…',
                    style: TextStyle(color: AppColors.textSec, fontSize: 13),
                  ),
                ],
              ),
            ],
            if (widget.attachments.isNotEmpty) ...[
              const SizedBox(height: 10),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (var i = 0; i < widget.attachments.length; i++)
                    _DraftAttachmentChip(
                      attachment: widget.attachments[i],
                      onRemove: () => widget.onRemoveAttachment(i),
                    ),
                ],
              ),
            ],
            if (widget.processedVoice != null) ...[
              const SizedBox(height: 10),
              _ProcessedVoicePreviewCard(
                preview: widget.processedVoice!,
                onDismiss: widget.onClearProcessedVoice,
              ),
            ],
            if (widget.recording || widget.hasRecordedAudio) ...[
              const SizedBox(height: 10),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: AppColors.lightIndigo,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Icon(
                          widget.recording
                              ? Icons.fiber_manual_record
                              : Icons.mic,
                          color: widget.recording
                              ? AppColors.errorRed
                              : AppColors.primaryBlue,
                          size: 20,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            widget.recording
                                ? 'Recording… tap stop when done'
                                : widget.processingVoice
                                    ? 'Processing voice with AI…'
                                    : widget.uploading
                                        ? 'Uploading recording…'
                                        : 'Recording ready',
                            style: const TextStyle(
                              color: AppColors.textMain,
                              fontSize: 13,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                        if (widget.hasRecordedAudio &&
                            !widget.processingVoice &&
                            !widget.uploading)
                          TextButton(
                            onPressed:
                                widget.saving ? null : widget.onDiscardVoice,
                            child: const Text('Discard'),
                          ),
                      ],
                    ),
                    if (widget.hasRecordedAudio &&
                        !widget.processingVoice &&
                        !widget.uploading) ...[
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Expanded(
                            child: OutlinedButton.icon(
                              onPressed: widget.saving
                                  ? null
                                  : widget.onAttachRecording,
                              icon: const Icon(Icons.upload_file, size: 18),
                              label: const Text('Attach recording'),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: FilledButton.icon(
                              onPressed:
                                  widget.saving ? null : widget.onUploadVoice,
                              style: FilledButton.styleFrom(
                                backgroundColor: AppColors.primaryBlue,
                              ),
                              icon: const Icon(Icons.auto_awesome, size: 18),
                              label: const Text('Upload to AI'),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      const Text(
                        'Attach posts audio only. Upload to AI adds summary & transcript.',
                        style: TextStyle(
                          color: AppColors.textSec,
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
            const SizedBox(height: 4),
            _ReminderField(
              enabled: !widget.saving,
              checked: widget.setReminder,
              reminderAt: widget.reminderAt,
              onChecked: widget.onSetReminderChanged,
              onPick: widget.onPickReminder,
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                IconButton(
                  onPressed: widget.saving ? null : widget.onAttachFile,
                  icon: const Icon(Icons.attach_file, color: AppColors.textSec),
                  tooltip: 'Attach file',
                ),
                IconButton(
                  onPressed: widget.saving ? null : widget.onAttachImage,
                  icon: const Icon(Icons.image_outlined, color: AppColors.textSec),
                  tooltip: 'Attach image',
                ),
                const Spacer(),
                TextButton.icon(
                  onPressed: widget.saving ? null : widget.onToggleVoice,
                  style: TextButton.styleFrom(
                    backgroundColor: widget.recording
                        ? AppColors.errorRed.withValues(alpha: 0.12)
                        : AppColors.lightIndigo,
                    foregroundColor: widget.recording
                        ? AppColors.errorRed
                        : AppColors.primaryBlue,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                  icon: Icon(widget.recording ? Icons.stop : Icons.mic, size: 18),
                  label: Text(widget.recording ? 'Stop' : 'Voice Note'),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: canSave ? widget.onSave : null,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.primaryBlue,
                    disabledBackgroundColor: const Color(0xFFCBD5E1),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 12,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                  child: widget.saving
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Text('Save Note'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ProcessedVoicePreviewCard extends StatelessWidget {
  const _ProcessedVoicePreviewCard({
    required this.preview,
    required this.onDismiss,
  });

  final _ProcessedVoicePreview preview;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.lightIndigo,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFFC7D2FE)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Icon(Icons.auto_awesome, size: 18, color: AppColors.primaryBlue),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  preview.fileName?.isNotEmpty == true
                      ? preview.fileName!
                      : 'AI voice note ready',
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    color: AppColors.textMain,
                    fontSize: 13,
                  ),
                ),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: onDismiss,
                icon: const Icon(Icons.close, size: 18, color: AppColors.textSec),
              ),
            ],
          ),
          if (preview.summary.isNotEmpty) ...[
            const Text(
              'Summary',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: AppColors.primaryBlue,
                fontSize: 12,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              preview.summary,
              style: const TextStyle(
                color: AppColors.textMain,
                fontSize: 13,
                height: 1.35,
              ),
            ),
          ],
          if (preview.transcription.isNotEmpty) ...[
            const SizedBox(height: 10),
            const Text(
              'Transcription',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: AppColors.primaryBlue,
                fontSize: 12,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              preview.transcription,
              style: const TextStyle(
                color: AppColors.textMain,
                fontSize: 13,
                height: 1.35,
              ),
            ),
          ],
          const SizedBox(height: 6),
          const Text(
            'Shown here for review only — not duplicated into the note text.',
            style: TextStyle(color: AppColors.textSec, fontSize: 11),
          ),
        ],
      ),
    );
  }
}

class _DraftAttachmentChip extends StatelessWidget {
  const _DraftAttachmentChip({
    required this.attachment,
    required this.onRemove,
  });

  final _NoteAttachmentPreview attachment;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    if (attachment.isImage) {
      final imageProvider = attachment.localPath != null
          ? FileImage(File(attachment.localPath!)) as ImageProvider
          : (attachment.url.startsWith('http')
              ? NetworkImage(attachment.url)
              : FileImage(File(attachment.url)) as ImageProvider);
      return Stack(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Image(
              image: imageProvider,
              width: 72,
              height: 72,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => Container(
                width: 72,
                height: 72,
                color: AppColors.lightIndigo,
                child: const Icon(Icons.broken_image, color: AppColors.textSec),
              ),
            ),
          ),
          if (attachment.isPending)
            Positioned.fill(
              child: Container(
                color: Colors.black26,
                child: const Center(
                  child: SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
            ),
          Positioned(
            top: 2,
            right: 2,
            child: InkWell(
              onTap: onRemove,
              child: const CircleAvatar(
                radius: 10,
                backgroundColor: Colors.black54,
                child: Icon(Icons.close, size: 12, color: Colors.white),
              ),
            ),
          ),
        ],
      );
    }

    return Chip(
      avatar: Icon(
        attachment.fileType.toLowerCase().contains('m4a') ||
                attachment.fileType.toLowerCase().contains('mp3')
            ? Icons.mic
            : Icons.attach_file,
        size: 16,
        color: AppColors.primaryBlue,
      ),
      label: Text(
        attachment.isPending ? 'Uploading ${attachment.name}…' : attachment.name,
        style: const TextStyle(fontSize: 12),
      ),
      deleteIcon: const Icon(Icons.close, size: 16),
      onDeleted: onRemove,
    );
  }
}

class _ReminderField extends StatelessWidget {
  const _ReminderField({
    required this.enabled,
    required this.checked,
    required this.reminderAt,
    required this.onChecked,
    required this.onPick,
  });

  final bool enabled;
  final bool checked;
  final DateTime? reminderAt;
  final ValueChanged<bool> onChecked;
  final VoidCallback onPick;

  @override
  Widget build(BuildContext context) {
    final hasTime = reminderAt != null;
    final label = hasTime
        ? DateFormat('dd-MM-yyyy HH:mm').format(reminderAt!)
        : 'dd-mm-yyyy --:--';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            SizedBox(
              width: 28,
              height: 28,
              child: Checkbox(
                value: checked,
                onChanged: enabled ? (value) => onChecked(value ?? false) : null,
                activeColor: AppColors.primaryBlue,
                checkColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(4),
                ),
                side: const BorderSide(color: Color(0xFF94A3B8), width: 1.4),
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                visualDensity: VisualDensity.compact,
              ),
            ),
            const SizedBox(width: 6),
            GestureDetector(
              onTap: enabled ? () => onChecked(!checked) : null,
              child: const Text(
                'Set a reminder',
                style: TextStyle(
                  color: AppColors.textMain,
                  fontSize: 14,
                ),
              ),
            ),
          ],
        ),
        if (checked) ...[
          const SizedBox(height: 8),
          Material(
            color: Colors.white,
            borderRadius: BorderRadius.circular(8),
            child: InkWell(
              onTap: enabled ? onPick : null,
              borderRadius: BorderRadius.circular(8),
              child: InputDecorator(
                decoration: InputDecoration(
                  isDense: true,
                  filled: true,
                  fillColor: Colors.white,
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 12,
                  ),
                  suffixIcon: const Icon(
                    Icons.calendar_today_outlined,
                    size: 18,
                    color: AppColors.textSec,
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: const BorderSide(color: Color(0xFFE2E8F0)),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: const BorderSide(color: Color(0xFFE2E8F0)),
                  ),
                ),
                child: Text(
                  label,
                  style: TextStyle(
                    color: hasTime ? AppColors.textMain : AppColors.textGray,
                    fontSize: 14,
                  ),
                ),
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _TimelineEntry extends StatefulWidget {
  const _TimelineEntry({
    required this.dateLabel,
    required this.addedBy,
    required this.contactName,
    this.recordedAtIso,
    this.reminderLabel,
    required this.parsed,
    required this.isLast,
  });

  final String dateLabel;
  final String addedBy;
  final String contactName;
  final String? recordedAtIso;
  final String? reminderLabel;
  final ParsedTimelineNote parsed;
  final bool isLast;

  @override
  State<_TimelineEntry> createState() => _TimelineEntryState();
}

class _TimelineEntryState extends State<_TimelineEntry> {
  bool _expanded = false;

  IconData _iconFor(CallNoteKind? callKind) {
    switch (callKind) {
      case CallNoteKind.missed:
        return Icons.call_missed;
      case CallNoteKind.incoming:
        return Icons.call_received;
      case CallNoteKind.outgoing:
        return Icons.call_made;
      case null:
        break;
    }
    switch (widget.parsed.primaryIconKind) {
      case TimelineAttachmentKind.audio:
        return Icons.mic;
      case TimelineAttachmentKind.image:
        return Icons.image_outlined;
      case TimelineAttachmentKind.document:
        return Icons.attach_file;
      case null:
        return widget.parsed.hasVoiceContent
            ? Icons.mic
            : Icons.chat_bubble_outline;
    }
  }

  @override
  Widget build(BuildContext context) {
    final parsed = widget.parsed;
    final callKind = callNoteKind(parsed.introText);
    final intro = presentCallNote(parsed.introText.trim());
    final icon = _iconFor(callKind);
    final iconColor = switch (callKind) {
      CallNoteKind.missed => AppColors.errorRed,
      CallNoteKind.incoming => AppColors.successGreen,
      CallNoteKind.outgoing => AppColors.primaryBlue,
      null => AppColors.textSec,
    };
    final iconBg = callKind == null
        ? AppColors.lightIndigo
        : iconColor.withValues(alpha: 0.12);
    final bodyText = [
      if (intro.isNotEmpty) intro,
      if (parsed.summary != null && parsed.summary!.isNotEmpty)
        'Summary:\n${parsed.summary}',
      if (parsed.transcription != null && parsed.transcription!.isNotEmpty)
        'Transcription:\n${parsed.transcription}',
    ].join('\n\n');
    final needsToggle = bodyText.length > 220 || bodyText.split('\n').length > 8;

    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 40,
            child: Stack(
              alignment: Alignment.topCenter,
              children: [
                Positioned(
                  top: 20,
                  bottom: widget.isLast ? 24 : 0,
                  child: Container(width: 2, color: AppColors.lightIndigo),
                ),
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: iconBg,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(icon, size: 18, color: iconColor),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text.rich(
                    TextSpan(
                      style: const TextStyle(
                        color: AppColors.textSec,
                        fontSize: 12,
                      ),
                      children: [
                        TextSpan(text: widget.dateLabel),
                        const TextSpan(text: '  •  '),
                        TextSpan(
                          text: 'Added by ${widget.addedBy}',
                          style: const TextStyle(
                            fontWeight: FontWeight.bold,
                            color: AppColors.textMain,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 6),
                  Material(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(14),
                    elevation: 0.5,
                    shadowColor: Colors.black12,
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (widget.reminderLabel != null) ...[
                            Text(
                              widget.reminderLabel!,
                              style: TextStyle(
                                color: widget.reminderLabel!.startsWith('Overdue')
                                    ? AppColors.errorRed
                                    : const Color(0xFFB45309),
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 6),
                          ],
                          if (bodyText.isNotEmpty)
                            Text(
                              bodyText,
                              maxLines: _expanded || !needsToggle ? null : 6,
                              overflow: _expanded || !needsToggle
                                  ? TextOverflow.visible
                                  : TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: AppColors.textMain,
                                fontSize: 13,
                                height: 1.4,
                              ),
                            )
                          else if (parsed.attachments.isEmpty)
                            const Text(
                              '(Empty note)',
                              style: TextStyle(
                                color: AppColors.textSec,
                                fontSize: 13,
                              ),
                            ),
                          if (needsToggle) ...[
                            const SizedBox(height: 6),
                            GestureDetector(
                              onTap: () =>
                                  setState(() => _expanded = !_expanded),
                              child: Text(
                                _expanded ? 'Show less ▴' : 'Show more ▾',
                                style: const TextStyle(
                                  color: Color(0xFF6366F1),
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600,
                                  decoration: TextDecoration.underline,
                                ),
                              ),
                            ),
                          ],
                          if (parsed.images.isNotEmpty) ...[
                            const SizedBox(height: 10),
                            _ImageAttachments(images: parsed.images),
                          ],
                          if (parsed.documents.isNotEmpty) ...[
                            const SizedBox(height: 10),
                            Wrap(
                              spacing: 8,
                              runSpacing: 6,
                              children: parsed.documents
                                  .map(
                                    (d) => ActionChip(
                                      avatar: const Icon(
                                        Icons.attach_file,
                                        size: 16,
                                      ),
                                      label: Text(
                                        d.name,
                                        style: const TextStyle(fontSize: 12),
                                      ),
                                      onPressed: () => launchUrl(
                                        Uri.parse(d.openUrl),
                                        mode: LaunchMode.externalApplication,
                                      ),
                                    ),
                                  )
                                  .toList(),
                            ),
                          ],
                          if (parsed.audios.isNotEmpty) ...[
                            const SizedBox(height: 10),
                            ...parsed.audios.map(
                              (a) => Padding(
                                padding: const EdgeInsets.only(bottom: 6),
                                child: _AudioAttachmentRow(
                                  attachment: a,
                                  contactName: widget.contactName,
                                  recordedAtIso: widget.recordedAtIso,
                                  allAudios: parsed.audios,
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ImageAttachments extends StatelessWidget {
  const _ImageAttachments({required this.images});

  final List<TimelineAttachment> images;

  @override
  Widget build(BuildContext context) {
    final shown = images.take(3).toList();
    final more = images.length - shown.length;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (var i = 0; i < shown.length; i++)
          GestureDetector(
            onTap: () => _openFullscreen(context, images, i),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: Image.network(
                shown[i].url,
                width: 96,
                height: 96,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => Container(
                  width: 96,
                  height: 96,
                  color: AppColors.lightIndigo,
                  child: const Icon(Icons.broken_image, color: AppColors.textSec),
                ),
              ),
            ),
          ),
        if (more > 0)
          GestureDetector(
            onTap: () => _openFullscreen(context, images, 0),
            child: Container(
              width: 96,
              height: 96,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: AppColors.lightIndigo,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                '+$more',
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  color: AppColors.primaryBlue,
                ),
              ),
            ),
          ),
      ],
    );
  }

  void _openFullscreen(
    BuildContext context,
    List<TimelineAttachment> images,
    int index,
  ) {
    showDialog<void>(
      context: context,
      builder: (ctx) => Dialog(
        backgroundColor: Colors.black,
        insetPadding: const EdgeInsets.all(12),
        child: Stack(
          children: [
            PageView.builder(
              controller: PageController(initialPage: index),
              itemCount: images.length,
              itemBuilder: (_, i) => InteractiveViewer(
                child: Center(
                  child: Image.network(
                    images[i].url,
                    fit: BoxFit.contain,
                    errorBuilder: (_, __, ___) => const Icon(
                      Icons.broken_image,
                      color: Colors.white54,
                      size: 48,
                    ),
                  ),
                ),
              ),
            ),
            Positioned(
              top: 8,
              right: 8,
              child: IconButton(
                onPressed: () => Navigator.pop(ctx),
                icon: const Icon(Icons.close, color: Colors.white),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AudioAttachmentRow extends StatelessWidget {
  const _AudioAttachmentRow({
    required this.attachment,
    required this.contactName,
    required this.allAudios,
    this.recordedAtIso,
  });

  final TimelineAttachment attachment;
  final String contactName;
  final String? recordedAtIso;
  final List<TimelineAttachment> allAudios;

  Future<void> _openPlayer(BuildContext context) async {
    final queue = allAudios
        .where((a) => a.playbackUrl.trim().isNotEmpty)
        .map(
          (a) => RecordingQueueItem(
            uri: a.playbackUrl,
            contactName: contactName,
            phone: a.name,
            recordedAtIso: recordedAtIso,
          ),
        )
        .toList();
    if (queue.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No playable recording URL')),
      );
      return;
    }
    final start = queue.indexWhere((q) => q.uri == attachment.playbackUrl);
    await showRecordingPlayerSheet(
      context: context,
      queue: queue,
      startIndex: start < 0 ? 0 : start,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.lightIndigo,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        onTap: () => _openPlayer(context),
        borderRadius: BorderRadius.circular(10),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          child: Row(
            children: [
              const Icon(
                Icons.play_circle_filled,
                color: AppColors.primaryBlue,
                size: 36,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      attachment.name.isEmpty ? 'Voice note' : attachment.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.textMain,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const Text(
                      'Tap to play',
                      style: TextStyle(
                        color: AppColors.textSec,
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: AppColors.textSec),
            ],
          ),
        ),
      ),
    );
  }
}
