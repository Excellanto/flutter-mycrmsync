import 'dart:io';

import 'package:dio/dio.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/data/api_client.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/services/call_note_format.dart';
import 'package:path_provider/path_provider.dart';

/// Formats a call-status note body (parity with Android [CallStatusNotePoster]).
/// The timeline already names the contact, so the note itself does not repeat it.
String formatCallStatusNoteBody({
  required String direction,
  required int durationSec,
  required String startedAtIso,
  required String phone,
  required bool isWhatsAppCall,
  bool hasRecording = false,
}) {
  return formatWarmCallNote(
    direction: direction,
    durationSec: durationSec,
    startedAtIso: startedAtIso,
    phone: phone,
    isWhatsAppCall: isWhatsAppCall,
    hasRecording: hasRecording,
  );
}

/// Resolves a local/content/http recording URI to a file path suitable for multipart upload.
Future<String> resolveRecordingFileForUpload(String uri) async {
  final trimmed = uri.trim();
  if (trimmed.isEmpty) {
    throw Exception('No recording attached to this call');
  }

  if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
    final dir = await getTemporaryDirectory();
    final ext = _guessExt(trimmed);
    final out = File(
      '${dir.path}/crm_upload_${DateTime.now().millisecondsSinceEpoch}.$ext',
    );
    final dio = Dio(
      BaseOptions(
        connectTimeout: const Duration(seconds: 30),
        receiveTimeout: const Duration(minutes: 2),
      ),
    );
    await dio.download(trimmed, out.path);
    if (!out.existsSync() || out.lengthSync() == 0) {
      throw Exception('Could not download recording');
    }
    return out.path;
  }

  if (AppConfig.isAndroid) {
    final resolved = await NativeBridges.resolvePlayablePath(trimmed);
    if (resolved != null && resolved.isNotEmpty) {
      if (resolved.startsWith('http://') || resolved.startsWith('https://')) {
        return resolveRecordingFileForUpload(resolved);
      }
      final path = resolved.startsWith('file://')
          ? Uri.parse(resolved).toFilePath()
          : resolved;
      if (File(path).existsSync()) return path;
    }
  }

  final path = trimmed.startsWith('file://')
      ? Uri.parse(trimmed).toFilePath()
      : trimmed;
  if (!File(path).existsSync()) {
    throw Exception('Could not read call recording');
  }
  return path;
}

/// Process recording with AI and attach to contact timeline (Android Upload-to-AI parity).
Future<void> uploadRecordingToAi({
  required CrmApi api,
  required String contactId,
  required String recordingUri,
  required String contactDisplayName,
  required String direction,
  required int durationSec,
  required String startedAtIso,
  required String phone,
  required bool isWhatsAppCall,
}) async {
  final id = contactId.trim();
  if (id.isEmpty) {
    throw Exception('No CRM contact linked to this call');
  }

  final callStatusBody = formatCallStatusNoteBody(
    direction: direction,
    durationSec: durationSec,
    startedAtIso: startedAtIso,
    phone: phone,
    isWhatsAppCall: isWhatsAppCall,
    hasRecording: true,
  );

  final filePath = await resolveRecordingFileForUpload(recordingUri);
  try {
    final processed = await api.processVoiceNote(
      contactId: id,
      audioPath: filePath,
      existingNoteText: callStatusBody,
    );

    final voiceNoteId =
        '${processed['voice_note_id'] ?? processed['voiceNoteId'] ?? ''}'.trim();
    if (voiceNoteId.isEmpty) {
      throw Exception('Voice note processing returned no id');
    }

    final noteBody = '${processed['note_body'] ?? processed['noteBody'] ?? ''}'.trim();
    final summary = '${processed['summary'] ?? ''}'.trim();
    final transcription = '${processed['transcription'] ?? ''}'.trim();
    final fallbackBody = [
      callStatusBody.trimRight(),
      if (summary.isNotEmpty) ...['', summary],
      if (transcription.isNotEmpty) ...['', transcription],
    ].join('\n');

    await api.processMediaBatch(
      contactId: id,
      voiceNoteIds: [voiceNoteId],
      existingNoteText: noteBody.isNotEmpty ? noteBody : fallbackBody,
    );
  } finally {
    // Clean temp download only (cache copies from content:// are also under temp/cache).
    final f = File(filePath);
    if (filePath.contains('crm_upload_') && f.existsSync()) {
      try {
        await f.delete();
      } catch (_) {}
    }
  }
}

String _guessExt(String url) {
  final path = Uri.tryParse(url)?.path.toLowerCase() ?? url.toLowerCase();
  if (path.endsWith('.mp3')) return 'mp3';
  if (path.endsWith('.wav')) return 'wav';
  if (path.endsWith('.ogg')) return 'ogg';
  if (path.endsWith('.m4a')) return 'm4a';
  if (path.endsWith('.aac')) return 'aac';
  return 'm4a';
}
