import 'package:flutter_test/flutter_test.dart';
import 'package:mycrmsync/services/call_note_format.dart';

void main() {
  test('outgoing WhatsApp call reads as a short sentence', () {
    final text = formatWarmCallNote(
      direction: 'OUTGOING',
      durationSec: 23,
      startedAtIso: '2026-10-02T13:39:32.220+05:30',
      phone: '+919810023290',
      isWhatsAppCall: true,
    );
    expect(
      text,
      '↗  You called on WhatsApp  ·  23 sec\n'
      '+91 98100 23290  ·  2 Oct, 1:39 PM',
    );
    expect(callNoteKind(text), CallNoteKind.outgoing);
    expect(isWhatsAppCallNote(text), isTrue);
    expect(isDeviceCallNote(text), isFalse);
  });

  test('outgoing phone call and incoming call use different marks', () {
    final outgoing = formatWarmCallNote(
      direction: 'OUTGOING',
      durationSec: 99,
      startedAtIso: '2026-09-22T13:46:09.912+05:30',
      phone: '199',
      isWhatsAppCall: false,
    );
    expect(
      outgoing,
      '↗  You called  ·  1 min 39 sec\n'
      '199  ·  22 Sep, 1:46 PM',
    );
    expect(isDeviceCallNote(outgoing), isTrue);

    final incoming = formatWarmCallNote(
      direction: 'INCOMING',
      durationSec: 252,
      startedAtIso: '2026-10-02T15:10:00+05:30',
      phone: '+919810023290',
      isWhatsAppCall: true,
    );
    expect(incoming.startsWith('↙  Called you on WhatsApp  ·  4 min 12 sec'), isTrue);
    expect(callNoteKind(incoming), CallNoteKind.incoming);
  });

  test('missed calls drop the duration', () {
    final text = formatWarmCallNote(
      direction: 'MISSED',
      durationSec: 0,
      startedAtIso: '2026-10-02T15:10:00+05:30',
      phone: '+919810023290',
      isWhatsAppCall: false,
    );
    expect(
      text,
      '✕  You missed a call\n'
      '+91 98100 23290  ·  2 Oct, 3:10 PM',
    );
    expect(callNoteKind(text), CallNoteKind.missed);
  });

  test('older label blocks are rewritten for display', () {
    const legacy = '''
WhatsApp voice call
Contact: Wadhawan Smith
Direction: Outgoing
Status: Completed
When: 2026-10-02T13:39:32.220+05:30
Duration: 23s
Number: +919810023290
''';
    final text = presentCallNote(legacy);
    expect(text.startsWith('↗  You called on WhatsApp  ·  23 sec'), isTrue);
    expect(text.contains('+91 98100 23290'), isTrue);
    expect(callNoteKind(legacy), CallNoteKind.outgoing);

    const phone = '''
Device call log
Contact: Tanvi Smith
Direction: Outgoing
Status: Completed
When: 2026-09-22T13:46:09.912+05:30
Duration: 1:39
Number: 199
''';
    expect(
      presentCallNote(phone),
      '↗  You called  ·  1 min 39 sec\n'
      '199  ·  22 Sep, 1:46 PM',
    );
    expect(isDeviceCallNote(phone), isTrue);
  });
}
