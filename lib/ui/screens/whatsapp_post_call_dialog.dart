import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:record/record.dart';

class WhatsAppPostCallDialog extends ConsumerStatefulWidget {
  const WhatsAppPostCallDialog({
    super.key,
    required this.payload,
    required this.onClosed,
    this.onSaved,
  });

  final Map<String, dynamic> payload;
  final VoidCallback onClosed;
  final VoidCallback? onSaved;

  @override
  ConsumerState<WhatsAppPostCallDialog> createState() => _WhatsAppPostCallDialogState();
}

class _WhatsAppPostCallDialogState extends ConsumerState<WhatsAppPostCallDialog> {
  final _note = TextEditingController();
  final _imagePicker = ImagePicker();
  final _audioRecorder = AudioRecorder();
  final List<_QuickAttachment> _attachments = [];

  bool _saving = false;
  bool _uploading = false;
  bool _recording = false;
  bool _processingVoice = false;
  String? _recordingPath;
  _QuickVoicePreview? _processedVoice;
  bool _setReminder = false;
  DateTime? _reminderAt;

  static const _maxFileBytes = 50 * 1024 * 1024;

  String get _contactId => '${widget.payload['contactId'] ?? ''}'.trim();

  bool get _hasContact => _contactId.isNotEmpty;

  bool get _busy => _saving || _uploading || _processingVoice;

  @override
  void initState() {
    super.initState();
    final seed = '${widget.payload['noteSeed'] ?? ''}'.trim();
    if (seed.isNotEmpty) _note.text = seed;
  }

  @override
  void dispose() {
    _note.dispose();
    _audioRecorder.dispose();
    super.dispose();
  }

  List<String> get _voiceNoteIds => _attachments
      .where((a) => !a.isPending && a.voiceNoteId.isNotEmpty)
      .map((a) => a.voiceNoteId)
      .toList();

  Future<void> _save() async {
    if (!_hasContact) {
      widget.onClosed();
      return;
    }
    if (_recordingPath != null && !_recording && File(_recordingPath!).existsSync()) {
      final ok = await _attachRecordingWithoutAi(showSnack: false);
      if (!ok) return;
    }
    final text = _note.text.trim();
    final ids = _voiceNoteIds;
    if (text.isEmpty && ids.isEmpty) {
      widget.onClosed();
      return;
    }
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
      final contactName = '${widget.payload['displayName'] ?? ''}'.trim();
      String? reminderError;
      if (ids.isNotEmpty) {
        final saved = await api.processMediaBatch(
          contactId: _contactId,
          voiceNoteIds: ids,
          existingNoteText: text.isEmpty ? null : text,
        );
        if (reminderAt != null) {
          final noteId = '${saved['crm_note_id'] ?? ''}'.trim();
          final excerpt = text.isNotEmpty ? text : (_processedVoice?.summary ?? '').trim();
          try {
            await api.createContactReminder(
              contactId: _contactId,
              reminderAt: reminderAt,
              noteId: noteId.isEmpty ? null : noteId,
              body: excerpt.isEmpty ? null : excerpt,
              contactName: contactName.isEmpty ? null : contactName,
            );
          } catch (e) {
            reminderError = '$e';
          }
        }
      } else {
        await api.createContactNote(
          contactId: _contactId,
          body: text,
          reminderAt: reminderAt,
          contactName: contactName.isEmpty ? null : contactName,
        );
      }
      if (!mounted) return;
      if (reminderError != null) {
        _snack('Note saved. $reminderError');
      }
      if (widget.onSaved != null) {
        widget.onSaved!();
      } else {
        widget.onClosed();
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
      _reminderAt = DateTime(date.year, date.month, date.day, time.hour, time.minute);
    });
  }

  Future<bool> _attachRecordingWithoutAi({bool showSnack = true}) async {
    final path = _recordingPath;
    if (path == null || !File(path).existsSync()) {
      if (showSnack) _snack('No recording to upload');
      return false;
    }
    final name = path.split(RegExp(r'[\\/]')).last;
    final ok = await _uploadPickedFile(
      path: path,
      name: name.isEmpty ? 'voice_${DateTime.now().millisecondsSinceEpoch}.m4a' : name,
      mimeHint: 'audio/mp4',
    );
    if (!ok || !mounted) return false;
    setState(() {
      _recordingPath = null;
      _processedVoice = null;
    });
    try {
      await File(path).delete();
    } catch (_) {}
    if (showSnack) _snack('Recording attached. Save the note to post it.');
    return true;
  }

  Future<bool> _uploadPickedFile({
    required String path,
    required String name,
    required String mimeHint,
  }) async {
    if (!_hasContact) {
      _snack('This call is not linked to a contact yet.');
      return false;
    }
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
        RegExp(r'\.(jpg|jpeg|png|gif|webp|bmp)$', caseSensitive: false).hasMatch(name);
    final isAudio = mimeHint.startsWith('audio/') ||
        RegExp(r'\.(m4a|mp3|wav|aac|ogg|amr)$', caseSensitive: false).hasMatch(name);
    final pendingId = 'pending_${DateTime.now().microsecondsSinceEpoch}';

    setState(() {
      _uploading = true;
      if (isImage || isAudio) {
        _attachments.add(
          _QuickAttachment(
            voiceNoteId: pendingId,
            name: name,
            url: path,
            fileType: isImage ? 'image' : 'm4a',
            localPath: path,
            isPending: true,
          ),
        );
      }
    });

    try {
      final data = await ref.read(crmApiProvider).processContactFile(
            contactId: _contactId,
            filePath: path,
            fileName: name,
            mimeType: mimeHint,
          );
      final voiceNoteId = '${data['voice_note_id'] ?? data['voiceNoteId'] ?? ''}'.trim();
      if (voiceNoteId.isEmpty) {
        throw Exception('Upload succeeded but no voice_note_id returned');
      }
      final remoteUrl = _previewUrl(data);
      final fileType =
          '${data['file_type'] ?? data['fileType'] ?? (isImage ? 'image' : (isAudio ? 'm4a' : _extOf(name)))}'
              .trim();
      final fileName = '${data['file_name'] ?? data['fileName'] ?? name}'.trim();
      if (!mounted) return false;
      setState(() {
        final preview = _QuickAttachment(
          voiceNoteId: voiceNoteId,
          name: fileName.isEmpty ? name : fileName,
          url: remoteUrl.isNotEmpty ? remoteUrl : path,
          fileType: fileType.isEmpty ? (isImage ? 'image' : (isAudio ? 'm4a' : 'file')) : fileType,
          localPath: isImage || isAudio ? path : null,
        );
        final idx = _attachments.indexWhere((a) => a.voiceNoteId == pendingId);
        if (idx >= 0) {
          _attachments[idx] = preview;
        } else {
          _attachments.add(preview);
        }
      });
      return true;
    } catch (e) {
      if (mounted) {
        setState(() => _attachments.removeWhere((a) => a.voiceNoteId == pendingId));
      }
      _snack('$e');
      return false;
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _pickImage() async {
    try {
      final file = await _imagePicker.pickImage(
        source: ImageSource.gallery,
        imageQuality: 92,
        requestFullMetadata: false,
      );
      if (file == null || !mounted) return;
      final name = file.name.trim().isEmpty
          ? 'image_${DateTime.now().millisecondsSinceEpoch}.jpg'
          : file.name;
      final mime = (file.mimeType ?? '').trim().isNotEmpty
          ? file.mimeType!
          : _mimeForFileName(name, fallbackImage: true);
      await _uploadPickedFile(path: file.path, name: name, mimeHint: mime);
    } on PlatformException catch (e) {
      final code = e.code.toLowerCase();
      _snack(
        code.contains('denied') ||
                code.contains('restricted') ||
                code.contains('permission')
            ? 'Photo library access is turned off. Enable it in Settings to attach an image.'
            : (e.message ?? 'Could not open the photo library.'),
      );
    } catch (_) {
      _snack('Could not open the photo library.');
    }
  }

  Future<void> _pickVoiceNote() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['m4a', 'mp3', 'wav', 'aac', 'ogg', 'amr'],
        withData: false,
      );
      if (result == null || result.files.isEmpty || !mounted) return;
      final file = result.files.first;
      final path = file.path;
      if (path == null) {
        _snack('Could not access selected voice note');
        return;
      }
      final name = file.name.trim().isEmpty
          ? 'voice_note_${DateTime.now().millisecondsSinceEpoch}.m4a'
          : file.name;
      await _uploadPickedFile(
        path: path,
        name: name,
        mimeHint: _mimeForFileName(name),
      );
    } on PlatformException catch (e) {
      _snack(e.message ?? 'Could not open the file picker.');
    } catch (_) {
      _snack('Could not open the file picker.');
    }
  }

  Future<void> _pickFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['pdf', 'doc', 'docx', 'jpg', 'jpeg', 'png'],
        withData: false,
      );
      if (result == null || result.files.isEmpty || !mounted) return;
      final file = result.files.first;
      final path = file.path;
      if (path == null) {
        _snack('Could not access selected file');
        return;
      }
      await _uploadPickedFile(path: path, name: file.name, mimeHint: _mimeForFileName(file.name));
    } on PlatformException catch (e) {
      _snack(e.message ?? 'Could not open the file picker.');
    } catch (_) {
      _snack('Could not open the file picker.');
    }
  }

  Future<void> _toggleVoiceRecord() async {
    if (_recording) {
      try {
        final path = await _audioRecorder.stop();
        if (!mounted) return;
        setState(() {
          _recording = false;
          _recordingPath = path ?? _recordingPath;
        });
      } catch (_) {
        if (!mounted) return;
        setState(() => _recording = false);
        _snack('Could not stop the recording.');
      }
      return;
    }
    try {
      final mic = await Permission.microphone.request();
      if (!mic.isGranted) {
        _snack(
          mic.isPermanentlyDenied || mic.isRestricted
              ? 'Microphone access is turned off. Enable it in Settings to record voice notes.'
              : 'Microphone permission is required for voice notes.',
        );
        return;
      }
      final dir = await getTemporaryDirectory();
      final out = '${dir.path}/voice_note_${DateTime.now().millisecondsSinceEpoch}.m4a';
      await _audioRecorder.start(const RecordConfig(encoder: AudioEncoder.aacLc), path: out);
      if (!mounted) return;
      setState(() {
        _recording = true;
        _recordingPath = out;
      });
    } catch (_) {
      _snack('Could not start the voice recording.');
    }
  }

  Future<void> _uploadVoiceToAi() async {
    final path = _recordingPath;
    if (path == null || !File(path).existsSync()) {
      _snack('No recording to upload');
      return;
    }
    if (!_hasContact) {
      _snack('This call is not linked to a contact yet.');
      return;
    }
    setState(() => _processingVoice = true);
    try {
      final typed = _note.text.trim();
      final data = await ref.read(crmApiProvider).processVoiceNote(
            contactId: _contactId,
            audioPath: path,
            existingNoteText: typed.isEmpty ? null : typed,
          );
      final voiceNoteId = '${data['voice_note_id'] ?? data['voiceNoteId'] ?? ''}'.trim();
      if (voiceNoteId.isEmpty) throw Exception('Voice processing returned no id');
      final url = _previewUrl(data);
      final name = '${data['file_name'] ?? data['fileName'] ?? 'Voice note'}'.trim();
      final summary = '${data['summary'] ?? ''}'.trim();
      final transcription = '${data['transcription'] ?? ''}'.trim();
      if (!mounted) return;
      setState(() {
        _attachments.removeWhere((a) => a.isAudio);
        _attachments.add(
          _QuickAttachment(
            voiceNoteId: voiceNoteId,
            name: name.isEmpty ? 'Voice note' : name,
            url: url,
            fileType: 'm4a',
          ),
        );
        _processedVoice = _QuickVoicePreview(
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
      _snack('Voice note ready. Save the note to post the transcript.');
    } catch (e) {
      _snack('$e');
    } finally {
      if (mounted) setState(() => _processingVoice = false);
    }
  }

  void _discardRecording() {
    final path = _recordingPath;
    setState(() => _recordingPath = null);
    if (path != null) {
      File(path).delete().catchError((_) => File(path));
    }
  }

  void _removeAttachment(int index) {
    setState(() {
      if (index < 0 || index >= _attachments.length) return;
      final removed = _attachments.removeAt(index);
      if (_processedVoice?.voiceNoteId == removed.voiceNoteId) _processedVoice = null;
    });
  }

  void _clearProcessedVoice() {
    setState(() {
      final id = _processedVoice?.voiceNoteId;
      _processedVoice = null;
      if (id != null) _attachments.removeWhere((a) => a.voiceNoteId == id);
    });
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final name = '${widget.payload['displayName'] ?? 'WhatsApp contact'}';
    final dir = '${widget.payload['direction'] ?? ''}';
    final dur = widget.payload['durationSec'] ?? 0;
    final hasRecording = _recordingPath != null && !_recording;
    final canSave = _hasContact &&
        !_busy &&
        (_note.text.trim().isNotEmpty ||
            _attachments.any((a) => !a.isPending) ||
            hasRecording);

    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.86),
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                widget.payload['isWhatsAppCall'] == false
                    ? 'Add a note'
                    : 'WhatsApp call ended',
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Text('$name · $dir · ${dur}s', style: const TextStyle(color: AppColors.textSec)),
              if (!_hasContact) ...[
                const SizedBox(height: 12),
                const Text(
                  'This call is not linked to a CRM contact, so a note cannot be saved.',
                  style: TextStyle(color: AppColors.errorRed, fontSize: 13),
                ),
              ],
              const SizedBox(height: 16),
              TextField(
                controller: _note,
                maxLines: 4,
                enabled: _hasContact && !_busy,
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  hintText: 'Type a note or record a voice update...',
                ),
              ),
              if (_uploading) ...[
                const SizedBox(height: 10),
                const Row(
                  children: [
                    SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                    SizedBox(width: 10),
                    Text('Uploading attachment…', style: TextStyle(color: AppColors.textSec, fontSize: 13)),
                  ],
                ),
              ],
              if (_attachments.isNotEmpty) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (var i = 0; i < _attachments.length; i++)
                      _QuickAttachmentChip(
                        attachment: _attachments[i],
                        onRemove: () => _removeAttachment(i),
                      ),
                  ],
                ),
              ],
              if (_processedVoice != null) ...[
                const SizedBox(height: 10),
                _QuickVoiceCard(preview: _processedVoice!, onDismiss: _clearProcessedVoice),
              ],
              if (_recording || hasRecording) ...[
                const SizedBox(height: 10),
                _RecordingPanel(
                  recording: _recording,
                  processing: _processingVoice,
                  uploading: _uploading,
                  ready: hasRecording && !_processingVoice && !_uploading,
                  onDiscard: _busy ? null : _discardRecording,
                  onAttach: _busy ? null : () => _attachRecordingWithoutAi(),
                  onUploadAi: _busy ? null : _uploadVoiceToAi,
                ),
              ],
              const SizedBox(height: 8),
              _QuickReminderField(
                enabled: _hasContact && !_busy,
                checked: _setReminder,
                reminderAt: _reminderAt,
                onChecked: _onSetReminder,
                onPick: _pickReminderDateTime,
              ),
              const SizedBox(height: 4),
              Row(
                children: [
                  IconButton(
                    onPressed: !_hasContact || _busy ? null : _pickVoiceNote,
                    icon: const Icon(Icons.audio_file_outlined, color: AppColors.textSec),
                    tooltip: 'Upload voice note',
                  ),
                  IconButton(
                    onPressed: !_hasContact || _busy ? null : _pickFile,
                    icon: const Icon(Icons.attach_file, color: AppColors.textSec),
                    tooltip: 'Attach file',
                  ),
                  IconButton(
                    onPressed: !_hasContact || _busy ? null : _pickImage,
                    icon: const Icon(Icons.image_outlined, color: AppColors.textSec),
                    tooltip: 'Attach image',
                  ),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: !_hasContact || _busy ? null : _toggleVoiceRecord,
                    style: TextButton.styleFrom(
                      backgroundColor: _recording
                          ? AppColors.errorRed.withValues(alpha: 0.12)
                          : AppColors.lightIndigo,
                      foregroundColor: _recording ? AppColors.errorRed : AppColors.primaryBlue,
                    ),
                    icon: Icon(_recording ? Icons.stop : Icons.mic, size: 18),
                    label: Text(_recording ? 'Stop' : 'Voice Note'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  TextButton(onPressed: _busy ? null : widget.onClosed, child: const Text('Skip')),
                  const Spacer(),
                  FilledButton(
                    onPressed: canSave ? _save : null,
                    child: _saving
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Text('Save note'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _previewUrl(Map<String, dynamic> data) {
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
      final value = '${data[key] ?? ''}'.trim();
      if (value.isNotEmpty) return value;
    }
    return '';
  }

  String _extOf(String name) {
    final i = name.lastIndexOf('.');
    if (i < 0) return 'file';
    return name.substring(i + 1).toLowerCase();
  }

  String _mimeForFileName(String name, {bool fallbackImage = false}) {
    return switch (_extOf(name)) {
      'jpg' || 'jpeg' => 'image/jpeg',
      'png' => 'image/png',
      'gif' => 'image/gif',
      'webp' => 'image/webp',
      'bmp' => 'image/bmp',
      'pdf' => 'application/pdf',
      'doc' => 'application/msword',
      'docx' => 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'm4a' => 'audio/mp4',
      'mp3' => 'audio/mpeg',
      'wav' => 'audio/wav',
      'ogg' => 'audio/ogg',
      'amr' => 'audio/amr',
      _ => fallbackImage ? 'image/jpeg' : 'application/octet-stream',
    };
  }
}

class _QuickAttachment {
  const _QuickAttachment({
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
    final type = fileType.toLowerCase();
    return type == 'image' || type == 'jpg' || type == 'jpeg' || type == 'png' || type == 'gif' || type == 'webp';
  }

  bool get isAudio {
    final type = fileType.toLowerCase();
    return type == 'm4a' || type == 'mp3' || type == 'wav' || type == 'aac' || type.contains('audio');
  }
}

class _QuickVoicePreview {
  const _QuickVoicePreview({
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

class _RecordingPanel extends StatelessWidget {
  const _RecordingPanel({
    required this.recording,
    required this.processing,
    required this.uploading,
    required this.ready,
    required this.onDiscard,
    required this.onAttach,
    required this.onUploadAi,
  });

  final bool recording;
  final bool processing;
  final bool uploading;
  final bool ready;
  final VoidCallback? onDiscard;
  final VoidCallback? onAttach;
  final VoidCallback? onUploadAi;

  @override
  Widget build(BuildContext context) {
    final label = recording
        ? 'Recording… tap stop when done'
        : processing
            ? 'Processing voice with AI…'
            : uploading
                ? 'Uploading recording…'
                : 'Recording ready';
    return Container(
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
                recording ? Icons.fiber_manual_record : Icons.mic,
                color: recording ? AppColors.errorRed : AppColors.primaryBlue,
                size: 20,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(label, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500)),
              ),
              if (ready) TextButton(onPressed: onDiscard, child: const Text('Discard')),
            ],
          ),
          if (ready) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: onAttach,
                    icon: const Icon(Icons.upload_file, size: 18),
                    label: const Text('Attach'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: onUploadAi,
                    icon: const Icon(Icons.auto_awesome, size: 18),
                    label: const Text('Upload to AI'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            const Text(
              'Attach posts audio only. Upload to AI adds summary and transcript.',
              style: TextStyle(color: AppColors.textSec, fontSize: 11),
            ),
          ],
        ],
      ),
    );
  }
}

class _QuickVoiceCard extends StatelessWidget {
  const _QuickVoiceCard({required this.preview, required this.onDismiss});

  final _QuickVoicePreview preview;
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
                  preview.fileName?.isNotEmpty == true ? preview.fileName! : 'AI voice note ready',
                  style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
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
            const Text('Summary', style: TextStyle(fontWeight: FontWeight.bold, color: AppColors.primaryBlue, fontSize: 12)),
            const SizedBox(height: 4),
            Text(preview.summary, style: const TextStyle(fontSize: 13, height: 1.35)),
          ],
          if (preview.transcription.isNotEmpty) ...[
            const SizedBox(height: 10),
            const Text(
              'Transcription',
              style: TextStyle(fontWeight: FontWeight.bold, color: AppColors.primaryBlue, fontSize: 12),
            ),
            const SizedBox(height: 4),
            Text(preview.transcription, style: const TextStyle(fontSize: 13, height: 1.35)),
          ],
        ],
      ),
    );
  }
}

class _QuickReminderField extends StatelessWidget {
  const _QuickReminderField({
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
    final label = hasTime ? DateFormat('dd-MM-yyyy HH:mm').format(reminderAt!) : 'dd-mm-yyyy --:--';
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
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
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
                style: TextStyle(color: AppColors.textMain, fontSize: 14),
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
                  contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
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

class _QuickAttachmentChip extends StatelessWidget {
  const _QuickAttachmentChip({required this.attachment, required this.onRemove});

  final _QuickAttachment attachment;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    if (attachment.isImage) {
      final provider = attachment.localPath != null
          ? FileImage(File(attachment.localPath!)) as ImageProvider
          : (attachment.url.startsWith('http')
              ? NetworkImage(attachment.url)
              : FileImage(File(attachment.url)) as ImageProvider);
      return Stack(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Image(image: provider, width: 72, height: 72, fit: BoxFit.cover),
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
        attachment.isAudio ? Icons.mic : Icons.attach_file,
        size: 16,
        color: AppColors.primaryBlue,
      ),
      label: Text(
        attachment.isPending ? 'Uploading ${attachment.name}…' : attachment.name,
        style: const TextStyle(fontSize: 12),
      ),
      onDeleted: onRemove,
    );
  }
}
