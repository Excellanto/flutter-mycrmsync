import 'package:mycrmsync/models/add_contact_form.dart';

/// Rule-based parser for business-card OCR text (ported from Android BusinessCardTextParser).
class BusinessCardTextParser {
  BusinessCardTextParser._();

  static final _emailRegex = RegExp(
    r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}',
    caseSensitive: false,
  );
  static final _urlRegex = RegExp(
    r'(?:https?://|www\.)[^\s,;]+',
    caseSensitive: false,
  );
  static final _phoneRegex = RegExp(r'(?:\+?\d[\d\s().-]{6,}\d)');
  static final _postalCodeRegex = RegExp(
    r'\b(\d{5}(?:-\d{4})?|\d{6}|[A-Z]\d[A-Z]\s?\d[A-Z]\d)\b',
    caseSensitive: false,
  );
  static final _companySuffixRegex = RegExp(
    r'\b(inc\.?|llc\.?|ltd\.?|corp\.?|corporation|company|co\.?|gmbh|pvt\.?\s*ltd\.?|plc|llp)\b',
    caseSensitive: false,
  );
  static final _jobTitleRegex = RegExp(
    r'\b(director|manager|president|ceo|cfo|cto|engineer|consultant|founder|partner|vp|vice president|head of)\b',
    caseSensitive: false,
  );
  static final _streetRegex = RegExp(
    r'\b(st\.?|street|ave\.?|avenue|road|rd\.?|lane|ln\.?|drive|dr\.?|boulevard|blvd\.?|suite|ste\.?|floor|fl\.?|#)\b',
    caseSensitive: false,
  );
  static final _mobileHintRegex = RegExp(
    r'\b(mobile|cell|mob\.?|m\.)\b',
    caseSensitive: false,
  );
  static final _officeHintRegex = RegExp(
    r'\b(office|tel\.?|phone|ph\.?|t\.|work|direct)\b',
    caseSensitive: false,
  );

  static const _countryNames = {
    'india',
    'usa',
    'united states',
    'uk',
    'united kingdom',
    'canada',
    'australia',
    'germany',
    'france',
    'uae',
    'singapore',
  };

  static AddContactForm parse(String rawText) {
    final lines = rawText
        .split('\n')
        .map(_normalizeLine)
        .where((l) => l.isNotEmpty)
        .toList();
    if (lines.isEmpty) return const AddContactForm();

    final consumed = <int>{};
    final emails = <String>[];
    final phones = <({String number, bool mobilePreferred})>[];

    for (var i = 0; i < lines.length; i++) {
      final line = lines[i];
      for (final m in _emailRegex.allMatches(line)) {
        emails.add(m.group(0)!.toLowerCase());
        consumed.add(i);
      }
      for (final m in _urlRegex.allMatches(line)) {
        consumed.add(i);
        // URLs ignored for form fields.
        m.group(0);
      }
      for (final phone in _extractPhonesFromLine(line)) {
        phones.add(phone);
        consumed.add(i);
      }
    }

    final email = emails.isEmpty ? '' : emails.first;
    final phone = _pickPrimaryPhone(phones);

    final remaining = <({int index, String line})>[];
    for (var i = 0; i < lines.length; i++) {
      if (consumed.contains(i)) continue;
      final stripped = lines[i]
          .replaceAll(_emailRegex, '')
          .replaceAll(_urlRegex, '')
          .replaceAll(_phoneRegex, '')
          .trim()
          .replaceAll(RegExp(r'^[-|,;]+|[-|,;]+$'), '')
          .trim();
      if (stripped.isNotEmpty) remaining.add((index: i, line: stripped));
    }

    var companyName = '';
    final companyIdx = remaining.indexWhere(
      (e) => _companySuffixRegex.hasMatch(e.line),
    );
    if (companyIdx >= 0) {
      companyName = remaining[companyIdx].line;
      consumed.add(remaining[companyIdx].index);
    }

    final nameCandidate = () {
      for (final e in remaining) {
        if (consumed.contains(e.index)) continue;
        if (_jobTitleRegex.hasMatch(e.line)) continue;
        if (_streetRegex.hasMatch(e.line)) continue;
        if (_postalCodeRegex.hasMatch(e.line)) continue;
        if (_countryNames.contains(e.line.toLowerCase())) continue;
        if (_looksLikeName(e.line)) return e.line;
      }
      return '';
    }();

    final nameParts = _splitName(nameCandidate);
    if (nameCandidate.isNotEmpty) {
      for (final e in remaining) {
        if (e.line.toLowerCase() == nameCandidate.toLowerCase()) {
          consumed.add(e.index);
          break;
        }
      }
    }

    final addressLines = <String>[];
    var city = '';
    var state = '';
    var postalCode = '';
    var country = '';

    final unconsumed =
        remaining.where((e) => !consumed.contains(e.index)).toList();

    for (final e in unconsumed) {
      final postal = _postalCodeRegex.firstMatch(e.line)?.group(0);
      if (postal != null && postalCode.isEmpty) {
        postalCode = postal.toUpperCase().replaceAll(' ', '');
      }

      final lower = e.line.toLowerCase();
      if (country.isEmpty && _countryNames.contains(lower)) {
        country = e.line;
        consumed.add(e.index);
        continue;
      }

      final cityStateZip = RegExp(
        r'^(.+?),\s*([A-Za-z]{2,})\s+(\d{5}(?:-\d{4})?)',
      ).firstMatch(e.line);
      if (cityStateZip != null) {
        city = cityStateZip.group(1)!.trim();
        state = cityStateZip.group(2)!.trim();
        postalCode = cityStateZip.group(3)!.trim();
        consumed.add(e.index);
        continue;
      }

      final cityStateOnly = RegExp(r'^(.+?),\s*([A-Za-z]{2,})$').firstMatch(e.line);
      if (cityStateOnly != null && city.isEmpty) {
        city = cityStateOnly.group(1)!.trim();
        state = cityStateOnly.group(2)!.trim();
        consumed.add(e.index);
        continue;
      }

      if (_streetRegex.hasMatch(e.line)) {
        addressLines.add(e.line);
        consumed.add(e.index);
      }
    }

    for (final e in unconsumed) {
      if (consumed.contains(e.index)) continue;
      if (addressLines.isNotEmpty && !_countryNames.contains(e.line.toLowerCase())) {
        addressLines.add(e.line);
        consumed.add(e.index);
      }
    }

    final resolvedCompany = companyName.isNotEmpty
        ? companyName
        : () {
            for (final e in remaining) {
              if (consumed.contains(e.index)) continue;
              if (_jobTitleRegex.hasMatch(e.line)) continue;
              if (e.line.length < 3) continue;
              if (_looksLikeName(e.line)) continue;
              return e.line;
            }
            return '';
          }();

    return AddContactForm(
      firstName: nameParts.$1,
      lastName: nameParts.$2,
      email: email,
      phone: phone,
      companyName: resolvedCompany,
      address: addressLines.join(', '),
      city: city,
      state: state,
      postalCode: postalCode,
      country: country,
    );
  }

  static String _normalizeLine(String line) =>
      line.trim().replaceAll(RegExp(r'\s+'), ' ');

  static List<({String number, bool mobilePreferred})> _extractPhonesFromLine(
    String line,
  ) {
    final mobilePreferred = _mobileHintRegex.hasMatch(line);
    final officePreferred = _officeHintRegex.hasMatch(line);
    final out = <({String number, bool mobilePreferred})>[];
    for (final m in _phoneRegex.allMatches(line)) {
      final raw = m.group(0)!.trim();
      final digits = raw.replaceAll(RegExp(r'\D'), '');
      if (digits.length < 7) continue;
      out.add((
        number: _normalizePhone(raw) ?? raw,
        mobilePreferred: mobilePreferred && !officePreferred,
      ));
    }
    return out;
  }

  static String? _normalizePhone(String raw, {String defaultCc = '+91'}) {
    final cleaned = raw.trim().replaceAll(RegExp(r'[^0-9+]'), '');
    if (cleaned.isEmpty) return null;
    if (cleaned.startsWith('+')) {
      final digits = cleaned.substring(1).replaceAll(RegExp(r'\D'), '');
      return digits.length >= 7 && digits.length <= 20 ? '+$digits' : null;
    }
    var digitsOnly = cleaned.replaceAll(RegExp(r'\D'), '');
    if (digitsOnly.startsWith('0')) {
      digitsOnly = digitsOnly.replaceFirst(RegExp(r'^0+'), '');
    }
    final cc = defaultCc.replaceAll(RegExp(r'\D'), '');
    final normalized = '+$cc$digitsOnly';
    final nDigits = normalized.substring(1).replaceAll(RegExp(r'\D'), '');
    return nDigits.length >= 8 && nDigits.length <= 24 ? normalized : null;
  }

  static String _pickPrimaryPhone(
    List<({String number, bool mobilePreferred})> phones,
  ) {
    if (phones.isEmpty) return '';
    for (final p in phones) {
      if (p.mobilePreferred) return p.number;
    }
    return phones.first.number;
  }

  static bool _looksLikeName(String line) {
    if (line.length > 60) return false;
    final words = line.split(' ').where((w) => w.isNotEmpty).toList();
    if (words.isEmpty || words.length > 5) return false;
    if (RegExp(r'\d').hasMatch(line)) return false;
    return words.every((word) {
      final letters = word.replaceAll('.', '');
      return letters.isNotEmpty &&
          RegExp(r'^[A-Za-z]+$').hasMatch(letters) &&
          word[0].toUpperCase() == word[0];
    });
  }

  static (String, String) _splitName(String fullName) {
    final parts = fullName.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
    if (parts.isEmpty) return ('', '');
    if (parts.length == 1) return (parts[0], '');
    return (parts.first, parts.sublist(1).join(' '));
  }
}
