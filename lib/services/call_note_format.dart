import 'package:intl/intl.dart';

/// Direction of a call, used to pick the timeline icon and the note headline.
enum CallNoteKind { outgoing, incoming, missed }

const _outgoingMark = '↗';
const _incomingMark = '↙';
const _missedMark = '✕';

/// Warm, short call note posted to the contact timeline.
///
/// [contactDisplayName] is accepted so callers stay aligned with the Android
/// poster. The timeline already shows who the contact is, so the body does not
/// repeat the name.
String formatWarmCallNote({
  required String direction,
  required int durationSec,
  required String startedAtIso,
  required String phone,
  required bool isWhatsAppCall,
  bool hasRecording = false,
}) {
  final kind = callNoteKindFromDirection(direction);
  final missed = kind == CallNoteKind.missed;
  // WhatsApp duration is not captured, so a zero length is left off the note.
  final length = missed || (isWhatsAppCall && durationSec <= 0)
      ? null
      : durationWords(durationSec);
  final headline = _headline(kind, isWhatsAppCall, length);
  final when = formatCallWhen(startedAtIso);
  final number = formatCallPhone(phone);
  final meta = [
    if (number.isNotEmpty) number,
    if (when.isNotEmpty) when,
  ].join('  ·  ');
  return [
    headline,
    if (meta.isNotEmpty) meta,
    if (hasRecording) 'Recording is on this phone',
  ].join('\n');
}

CallNoteKind? callNoteKindFromDirection(String direction) {
  final d = direction.toUpperCase();
  if (d.contains('MISS')) return CallNoteKind.missed;
  // "OUTGOING" also contains "IN", so outgoing has to be tested first.
  if (d.contains('OUT')) return CallNoteKind.outgoing;
  if (d.contains('IN')) return CallNoteKind.incoming;
  return null;
}

/// Reads a stored note (new or the old label block) and returns its direction.
CallNoteKind? callNoteKind(String text) {
  final trimmed = text.trimLeft();
  if (trimmed.isEmpty) return null;
  final first = trimmed.split('\n').first.trimLeft();
  if (first.startsWith(_outgoingMark) || first.startsWith('↗️')) {
    return CallNoteKind.outgoing;
  }
  if (first.startsWith(_incomingMark) || first.startsWith('↙️')) {
    return CallNoteKind.incoming;
  }
  if (first.startsWith(_missedMark) || first.startsWith('❌')) {
    return CallNoteKind.missed;
  }
  if (!_isLegacyCallNote(trimmed)) return null;
  final status = _field(trimmed, 'Status');
  if (status != null && status.toUpperCase().contains('MISS')) {
    return CallNoteKind.missed;
  }
  final direction = _field(trimmed, 'Direction');
  if (direction == null) return null;
  return callNoteKindFromDirection(direction);
}

/// Rewrites the old "Direction / Status / When" block into the warm note.
/// Notes that are already warm, and notes that are not call logs, are unchanged.
String presentCallNote(String text) {
  if (!_isLegacyCallNote(text)) return text;
  final lines = text.trim().split('\n');
  final whatsApp = lines.first.trim().toLowerCase().startsWith('whatsapp');
  var direction = '';
  var whenIso = '';
  var durationRaw = '';
  var phone = '';
  var hasRecording = false;
  final extra = <String>[];
  var headerDone = false;
  for (var i = 1; i < lines.length; i++) {
    final line = lines[i];
    if (!headerDone && _isLegacyField(line)) {
      final lower = line.trim().toLowerCase();
      final value = line.contains(':') ? line.split(':').skip(1).join(':').trim() : '';
      if (lower.startsWith('direction:')) {
        direction = value;
      } else if (lower.startsWith('when:')) {
        whenIso = value;
      } else if (lower.startsWith('duration:')) {
        durationRaw = value;
      } else if (lower.startsWith('number:')) {
        phone = value;
      } else if (lower.startsWith('recording:')) {
        hasRecording = true;
      } else if (lower.startsWith('status:') && value.toUpperCase().contains('MISS')) {
        direction = 'MISSED';
      }
    } else if (!headerDone && line.trim().isEmpty) {
      continue;
    } else {
      headerDone = true;
      extra.add(line);
    }
  }
  final seconds = _parseLegacyDuration(durationRaw) ?? 0;
  final warm = formatWarmCallNote(
    direction: direction,
    durationSec: seconds,
    startedAtIso: whenIso,
    phone: phone == '—' || phone == '-' ? '' : phone,
    isWhatsAppCall: whatsApp,
    hasRecording: hasRecording,
  );
  final rest = extra.join('\n').trim();
  if (rest.isEmpty) return warm;
  return '$warm\n\n$rest';
}

bool isWhatsAppCallNote(String text) => text.toLowerCase().contains('whatsapp');

bool isDeviceCallNote(String text) {
  if (isWhatsAppCallNote(text)) return false;
  final lower = text.toLowerCase();
  if (lower.contains('device call') || lower.contains('call log')) return true;
  return callNoteKind(text) != null;
}

String durationWords(int durationSec) {
  final sec = durationSec < 0 ? 0 : durationSec;
  final h = sec ~/ 3600;
  final m = (sec % 3600) ~/ 60;
  final s = sec % 60;
  if (h > 0 && m > 0) return '$h hr $m min';
  if (h > 0) return '$h hr';
  if (m > 0 && s > 0) return '$m min $s sec';
  if (m > 0) return '$m min';
  return '$s sec';
}

String formatCallWhen(String iso) {
  final raw = iso.trim();
  if (raw.isEmpty) return '';
  try {
    final dt = DateTime.parse(raw).toLocal();
    return DateFormat('d MMM, h:mm a', 'en_US').format(dt);
  } catch (_) {
    return raw;
  }
}

String formatCallPhone(String phone) {
  final raw = phone.trim();
  if (raw.isEmpty || raw == '—' || raw == '-') return '';
  final compact = raw.replaceAll(RegExp(r'[\s-]'), '');
  final india = RegExp(r'^\+91(\d{10})$').firstMatch(compact);
  if (india != null) {
    final n = india.group(1)!;
    return '+91 ${n.substring(0, 5)} ${n.substring(5)}';
  }
  return raw;
}

String _headline(CallNoteKind? kind, bool isWhatsAppCall, String? duration) {
  final length = duration == null ? '' : '  ·  $duration';
  switch (kind) {
    case CallNoteKind.missed:
      return isWhatsAppCall
          ? '$_missedMark  You missed a WhatsApp call'
          : '$_missedMark  You missed a call';
    case CallNoteKind.incoming:
      return isWhatsAppCall
          ? '$_incomingMark  Called you on WhatsApp$length'
          : '$_incomingMark  Called you$length';
    case CallNoteKind.outgoing:
      return isWhatsAppCall
          ? '$_outgoingMark  You called on WhatsApp$length'
          : '$_outgoingMark  You called$length';
    case null:
      return isWhatsAppCall ? 'WhatsApp call$length' : 'Phone call$length';
  }
}

bool _isLegacyCallNote(String text) {
  final first = text.trimLeft().split('\n').first.trim().toLowerCase();
  return first == 'whatsapp voice call' || first == 'device call log';
}

bool _isLegacyField(String line) {
  final lower = line.trim().toLowerCase();
  return lower.startsWith('contact:') ||
      lower.startsWith('direction:') ||
      lower.startsWith('status:') ||
      lower.startsWith('when:') ||
      lower.startsWith('duration:') ||
      lower.startsWith('number:') ||
      lower.startsWith('recording:');
}

String? _field(String text, String label) {
  final match = RegExp(
    '^$label:\\s*(.+)\$',
    caseSensitive: false,
    multiLine: true,
  ).firstMatch(text);
  final value = match?.group(1)?.trim();
  if (value == null || value.isEmpty) return null;
  return value;
}

int? _parseLegacyDuration(String raw) {
  final t = raw.trim();
  if (t.isEmpty || t == '—' || t == '-' || t == '–') return null;
  final seconds = RegExp(r'^(\d+)\s*s$', caseSensitive: false).firstMatch(t);
  if (seconds != null) return int.parse(seconds.group(1)!);
  final clock = RegExp(r'^(\d+):(\d{2})$').firstMatch(t);
  if (clock != null) {
    return int.parse(clock.group(1)!) * 60 + int.parse(clock.group(2)!);
  }
  return null;
}
