// Classifies contact-note attachments and strips media URLs from intro text
// (parity with Android TimelineNoteParser).

enum TimelineAttachmentKind { image, audio, document }

class TimelineAttachment {
  const TimelineAttachment({
    required this.url,
    required this.name,
    required this.kind,
    this.shortUrl,
    this.fileType,
    this.voiceNoteId,
  });

  final String url;
  final String? shortUrl;
  final String name;
  final TimelineAttachmentKind kind;
  final String? fileType;
  final String? voiceNoteId;

  String get openUrl => (shortUrl != null && shortUrl!.isNotEmpty) ? shortUrl! : url;
  String get playbackUrl => url;
}

class ParsedTimelineNote {
  const ParsedTimelineNote({
    this.introText = '',
    this.summary,
    this.transcription,
    this.attachments = const [],
  });

  final String introText;
  final String? summary;
  final String? transcription;
  final List<TimelineAttachment> attachments;

  bool get hasVoiceContent =>
      (summary != null && summary!.trim().isNotEmpty) ||
      (transcription != null && transcription!.trim().isNotEmpty);

  TimelineAttachmentKind? get primaryIconKind {
    if (attachments.any((a) => a.kind == TimelineAttachmentKind.audio) ||
        hasVoiceContent) {
      return TimelineAttachmentKind.audio;
    }
    if (attachments.any((a) => a.kind == TimelineAttachmentKind.image)) {
      return TimelineAttachmentKind.image;
    }
    if (attachments.any((a) => a.kind == TimelineAttachmentKind.document)) {
      return TimelineAttachmentKind.document;
    }
    return null;
  }

  List<TimelineAttachment> get images =>
      attachments.where((a) => a.kind == TimelineAttachmentKind.image).toList();

  List<TimelineAttachment> get documents =>
      attachments.where((a) => a.kind == TimelineAttachmentKind.document).toList();

  List<TimelineAttachment> get audios =>
      attachments.where((a) => a.kind == TimelineAttachmentKind.audio).toList();
}

class TimelineNoteParser {
  TimelineNoteParser._();

  static const _imageTypes = {'jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'};
  static const _audioTypes = {'mp3', 'wav', 'm4a', 'aac', 'ogg', 'webm', 'amr'};
  static const _docTypes = {'pdf', 'doc', 'docx'};

  static final _imgSrc = RegExp(
    r'''<img[^>]+src=["']([^"']+)["']''',
    caseSensitive: false,
  );
  static final _href = RegExp(
    r'''<a[^>]+href=["']([^"']+)["']''',
    caseSensitive: false,
  );
  static final _bareUrl = RegExp(r'(https?://[^\s<>"'']+)', caseSensitive: false);
  static final _fileAttachmentLine = RegExp(
    r'^\s*File Attachment:\s*.+$',
    multiLine: true,
    caseSensitive: false,
  );
  static final _attachmentUrlLine = RegExp(
    r'^\s*(?:Attachment|Recording URL|Recording):\s*(https?://\S+)\s*$',
    multiLine: true,
    caseSensitive: false,
  );

  static ParsedTimelineNote parse(Map<String, dynamic> note) {
    final rawBody = '${note['body'] ?? note['content'] ?? note['note'] ?? note['html'] ?? ''}';
    final structured = <TimelineAttachment>[];
    final rawAtts = note['attachments'];
    if (rawAtts is List) {
      for (final a in rawAtts) {
        if (a is Map) {
          final classified = _classifyStructured(Map<String, dynamic>.from(a));
          if (classified != null) structured.add(classified);
        } else if (a is String && a.trim().isNotEmpty) {
          structured.add(_classifyUrl(a.trim()));
        }
      }
    }

    final legacy = <TimelineAttachment>[];
    for (final m in _imgSrc.allMatches(rawBody)) {
      legacy.add(_classifyUrl(m.group(1)!.trim()));
    }
    for (final m in _href.allMatches(rawBody)) {
      legacy.add(_classifyUrl(m.group(1)!.trim()));
    }
    for (final m in _attachmentUrlLine.allMatches(rawBody)) {
      legacy.add(_classifyUrl(m.group(1)!.trim()));
    }
    for (final m in _bareUrl.allMatches(rawBody)) {
      final url = m.group(1)!.trimRight().replaceAll(RegExp(r'[.,;)]+$'), '');
      if (_looksLikeAttachmentUrl(url)) legacy.add(_classifyUrl(url));
    }

    final byUrl = <String, TimelineAttachment>{};
    for (final a in [...structured, ...legacy]) {
      byUrl.putIfAbsent(a.url, () => a);
    }
    final attachments = byUrl.values.toList();

    var working = rawBody;
    for (final a in attachments) {
      working = _removeUrlFromHtml(working, a.url);
      if (a.shortUrl != null) working = _removeUrlFromHtml(working, a.shortUrl!);
    }
    working = working
        .replaceAll(_imgSrc, '')
        .replaceAll(_href, '')
        .replaceAll(_fileAttachmentLine, '')
        .replaceAll(_attachmentUrlLine, '');

    final plain = _htmlToPlain(working).trim();
    final explicitSummary = '${note['summary'] ?? ''}'.trim();
    final explicitTranscription = '${note['transcription'] ?? ''}'.trim();
    final sections = _extractSections(plain);

    return ParsedTimelineNote(
      introText: sections.$1,
      summary: explicitSummary.isNotEmpty ? explicitSummary : sections.$2,
      transcription:
          explicitTranscription.isNotEmpty ? explicitTranscription : sections.$3,
      attachments: attachments,
    );
  }

  static TimelineAttachment? _classifyStructured(Map<String, dynamic> att) {
    final long = '${att['fileslongurl'] ?? att['file_long_url'] ?? att['fileLongUrl'] ?? att['recording_url_long'] ?? att['recordingUrlLong'] ?? att['file_url'] ?? att['fileUrl'] ?? att['url'] ?? ''}'
        .trim();
    final short = '${att['fileshorturl'] ?? att['file_short_url'] ?? att['fileShortUrl'] ?? att['recording_url'] ?? att['recordingUrl'] ?? ''}'
        .trim();
    final playback = long.isNotEmpty ? long : short;
    if (playback.isEmpty) return null;
    final fileType = '${att['filetype'] ?? att['file_type'] ?? att['fileType'] ?? ''}'
        .trim()
        .toLowerCase();
    final kind = _kindFrom(fileType, playback);
    final name = _displayName(playback);
    return TimelineAttachment(
      url: playback,
      shortUrl: short.isNotEmpty && short != playback ? short : null,
      name: name == 'Attachment'
          ? '${fileType.isEmpty ? 'file' : fileType} attachment'
          : name,
      kind: kind,
      fileType: fileType.isEmpty ? null : fileType,
      voiceNoteId:
          '${att['voice_note_id'] ?? att['voiceNoteId'] ?? ''}'.trim().isEmpty
              ? null
              : '${att['voice_note_id'] ?? att['voiceNoteId']}'.trim(),
    );
  }

  static TimelineAttachmentKind _kindFrom(String fileType, String url) {
    if (_imageTypes.contains(fileType) || _isImageUrl(url)) {
      return TimelineAttachmentKind.image;
    }
    if (_audioTypes.contains(fileType) || _isAudioUrl(url)) {
      return TimelineAttachmentKind.audio;
    }
    if (_docTypes.contains(fileType) || _isDocUrl(url)) {
      return TimelineAttachmentKind.document;
    }
    return TimelineAttachmentKind.document;
  }

  static bool _looksLikeAttachmentUrl(String url) {
    final lower = url.toLowerCase();
    return lower.contains('.jpg') ||
        lower.contains('.jpeg') ||
        lower.contains('.png') ||
        lower.contains('.gif') ||
        lower.contains('.webp') ||
        lower.contains('.pdf') ||
        lower.contains('.doc') ||
        lower.contains('.mp3') ||
        lower.contains('.wav') ||
        lower.contains('.m4a') ||
        lower.contains('voice-note') ||
        lower.contains('voicenote') ||
        lower.contains('/vn') ||
        lower.contains('/surl/') ||
        lower.contains('recording');
  }

  static TimelineAttachment _classifyUrl(String url) {
    final kind = _isImageUrl(url)
        ? TimelineAttachmentKind.image
        : _isAudioUrl(url)
            ? TimelineAttachmentKind.audio
            : TimelineAttachmentKind.document;
    return TimelineAttachment(url: url, name: _displayName(url), kind: kind);
  }

  static bool _isImageUrl(String url) =>
      RegExp(r'\.(jpg|jpeg|png|gif|webp|bmp)(\?|$)', caseSensitive: false)
          .hasMatch(url);

  static bool _isAudioUrl(String url) {
    final lower = url.toLowerCase();
    return RegExp(r'\.(mp3|wav|m4a|aac|ogg|webm|amr)(\?|$)').hasMatch(lower) ||
        lower.contains('voice-note') ||
        lower.contains('voicenote') ||
        lower.contains('/vn') ||
        lower.contains('/surl/') ||
        lower.contains('recording');
  }

  static bool _isDocUrl(String url) =>
      RegExp(r'\.(pdf|doc|docx)(\?|$)', caseSensitive: false).hasMatch(url);

  static String _displayName(String url) {
    final pathName = url.split('?').first.split('/').last;
    if (pathName.isNotEmpty && pathName.contains('.')) return pathName;
    return 'Attachment';
  }

  static String _removeUrlFromHtml(String html, String url) {
    if (url.isEmpty) return html;
    var out = html.replaceAll(url, '');
    out = out.replaceAll(
      RegExp(
        '''<img[^>]*src=["']${RegExp.escape(url)}["'][^>]*>''',
        caseSensitive: false,
      ),
      '',
    );
    out = out.replaceAll(
      RegExp(
        '''<a[^>]*href=["']${RegExp.escape(url)}["'][^>]*>.*?</a>''',
        caseSensitive: false,
      ),
      '',
    );
    return out;
  }

  static String _htmlToPlain(String html) {
    if (html.isEmpty) return '';
    return html
        .replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'</p\s*>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'<[^>]+>'), '')
        .replaceAll('&nbsp;', ' ')
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&quot;', '"')
        .replaceAll(RegExp(r'\n{3,}'), '\n\n')
        .trim();
  }

  /// Returns (intro, summary, transcription).
  static (String, String?, String?) _extractSections(String plainBody) {
    if (plainBody.isEmpty) return ('', null, null);

    final callMatch = RegExp(
      r'(.*?)(?:\n\s*)?📝\s*Call transcription \(AI,[^)]+\)\s*\n(.+)',
      caseSensitive: false,
      dotAll: true,
    ).firstMatch(plainBody);
    if (callMatch != null) {
      return (callMatch.group(1)!.trim(), null, callMatch.group(2)!.trim());
    }

    final parts = plainBody.split(
      RegExp(
        r'\n\s*(?:\*\*)?(?:Transcription|Transcript)(?:\*\*)?\s*:\s*',
        multiLine: true,
        caseSensitive: false,
      ),
    );
    if (parts.length == 2) {
      final before = parts[0].trim();
      final transcription = parts[1].trim();
      final summaryInline = RegExp(
        r'^\s*(.*?)(?:\*\*)?Summary(?:\*\*)?\s*:\s*(.+)$',
        caseSensitive: false,
        dotAll: true,
      ).firstMatch(before);
      if (summaryInline != null) {
        return (
          summaryInline.group(1)!.trim(),
          summaryInline.group(2)!.trim(),
          transcription.isEmpty ? null : transcription,
        );
      }
    }

    final summaryHeader = RegExp(
      r'(?:^|\n)\s*(?:\*\*)?Summary(?:\*\*)?\s*:?\s*\n',
      multiLine: true,
      caseSensitive: false,
    ).firstMatch(plainBody);
    final transcriptionHeader = RegExp(
      r'(?:^|\n)\s*(?:\*\*)?(?:Transcription|Transcript)(?:\*\*)?\s*:?\s*\n',
      multiLine: true,
      caseSensitive: false,
    ).firstMatch(plainBody);

    if (summaryHeader != null || transcriptionHeader != null) {
      final introEnd = [
        if (summaryHeader != null) summaryHeader.start,
        if (transcriptionHeader != null) transcriptionHeader.start,
      ].reduce((a, b) => a < b ? a : b);
      final intro = plainBody.substring(0, introEnd).trim();
      String? summary;
      String? transcription;
      if (summaryHeader != null) {
        final start = summaryHeader.end;
        final end = transcriptionHeader?.start ?? plainBody.length;
        summary = plainBody.substring(start, end).trim();
        if (summary.isEmpty) summary = null;
      }
      if (transcriptionHeader != null) {
        transcription = plainBody.substring(transcriptionHeader.end).trim();
        if (transcription.isEmpty) transcription = null;
      }
      return (intro, summary, transcription);
    }

    return (plainBody, null, null);
  }
}
