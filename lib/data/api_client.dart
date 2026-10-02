import 'package:dio/dio.dart';
import 'package:http_parser/http_parser.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/data/session_repository.dart';

typedef OnUnauthorized = void Function();

class ApiClient {
  ApiClient({required this.sessionRepository, this.onUnauthorized}) {
    _dio = Dio(
      BaseOptions(
        baseUrl: AppConfig.apiBaseUrl,
        connectTimeout: const Duration(seconds: 30),
        receiveTimeout: const Duration(seconds: 60),
        headers: {'Accept': 'application/json'},
      ),
    );
    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          final session = await sessionRepository.load();
          if (session != null && session.accessToken.isNotEmpty) {
            options.headers['Authorization'] = session.authorization;
          }
          handler.next(options);
        },
        onError: (error, handler) async {
          if (error.response?.statusCode == 401) {
            final path = error.requestOptions.path;
            if (!path.contains('/api/auth/')) {
              onUnauthorized?.call();
            }
          }
          handler.next(error);
        },
      ),
    );
  }

  final SessionRepository sessionRepository;
  final OnUnauthorized? onUnauthorized;
  late final Dio _dio;

  Dio get dio => _dio;
}

class AuthApi {
  AuthApi(this._client);

  final ApiClient _client;

  Future<void> sendOtp(String email) async {
    await _client.dio.post('api/auth/email-otp/send', data: {'email': email});
  }

  Future<SessionData> verifyOtp({
    required String email,
    required String otp,
  }) async {
    final response = await _client.dio.post(
      'api/auth/email-otp/verify',
      data: {'email': email, 'otp': otp},
    );
    final body = Map<String, dynamic>.from(response.data as Map);
    final data = body['data'] is Map
        ? Map<String, dynamic>.from(body['data'] as Map)
        : body;
    return SessionData.fromVerifyJson(data);
  }
}

class CrmApi {
  CrmApi(this._client);

  final ApiClient _client;

  Future<List<Map<String, dynamic>>> getUsers() async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId?.toString() ?? session?.effectiveUserId;
    final response = await _client.dio.get(
      'api/crm/users',
      queryParameters: {if (userId != null) 'user_id': userId},
    );
    return _extractList(response.data, keys: ['users', 'data', 'items']);
  }

  /// Tasks and reminder-tasks via `GET api/crm/tasks`.
  ///
  /// [kind] is `all`, `tasks`, or `reminders`. [status] is `open`,
  /// `in_progress`, `completed`, `cancelled`, or `all`.
  Future<List<Map<String, dynamic>>> getTasks({
    String status = 'open',
    String kind = 'all',
  }) async {
    final response = await _client.dio.get(
      'api/crm/tasks',
      queryParameters: {
        'user_id': await _currentUserId(),
        'status': status,
        'kind': kind,
      },
    );
    return _extractList(response.data, keys: ['tasks', 'data', 'items']);
  }

  Future<Map<String, dynamic>> createTask({
    required String title,
    required DateTime dueAt,
    String? body,
    String? contactId,
    String? contactName,
    List<int> assigneeIds = const [],
    String status = 'open',
  }) async {
    return _mutateTask('api/crm/tasks/add', {
      'user_id': await _currentUserId(),
      'title': title.trim(),
      if (body != null && body.trim().isNotEmpty) 'body': body.trim(),
      'due_at': dueAt.toIso8601String(),
      'status': status,
      if (contactId != null && contactId.trim().isNotEmpty)
        'contactId': contactId.trim(),
      if (contactName != null && contactName.trim().isNotEmpty)
        'contact_name': contactName.trim(),
      'assignee_ids': assigneeIds,
    }, fallback: 'Could not save the task.');
  }

  Future<Map<String, dynamic>> updateTask({
    required String taskId,
    required String title,
    required DateTime dueAt,
    String? body,
    String? contactId,
    String? contactName,
    List<int> assigneeIds = const [],
    String status = 'open',
  }) async {
    return _mutateTask('api/crm/tasks/update', {
      'user_id': await _currentUserId(),
      'taskId': taskId,
      'title': title.trim(),
      'body': body?.trim() ?? '',
      'due_at': dueAt.toIso8601String(),
      'status': status,
      'contactId': contactId?.trim() ?? '',
      'contact_name': contactName?.trim() ?? '',
      'assignee_ids': assigneeIds,
    }, fallback: 'Could not update the task.');
  }

  Future<void> updateTaskStatus(String taskId, String status) async {
    await _mutateTask('api/crm/tasks/status', {
      'user_id': await _currentUserId(),
      'taskId': taskId,
      'status': status,
    }, fallback: 'Could not change the task status.');
  }

  Future<void> completeTask(String taskId) async {
    await _mutateTask('api/crm/tasks/complete', {
      'user_id': await _currentUserId(),
      'taskId': taskId,
    }, fallback: 'Could not complete the task.');
  }

  Future<Object?> _currentUserId() async {
    final session = await _client.sessionRepository.load();
    return _jsonUserId(session?.authUserId ?? session?.effectiveUserId);
  }

  Future<Map<String, dynamic>> _mutateTask(
    String path,
    Map<String, dynamic> data, {
    required String fallback,
  }) async {
    try {
      final response = await _client.dio.post(path, data: data);
      final root = _asMap(response.data);
      if (root['success'] == false || root['status'] == false) {
        final message = '${root['message'] ?? ''}'.trim();
        throw Exception(message.isEmpty ? fallback : message);
      }
      final payload = root['data'];
      if (payload is Map && payload['task'] is Map) {
        return Map<String, dynamic>.from(payload['task'] as Map);
      }
      return root;
    } on DioException catch (e) {
      throw Exception(_dioErrorMessage(e, fallback: fallback));
    }
  }

  Future<List<Map<String, dynamic>>> searchContacts({
    String query = '',
    int page = 1,
    int pageLimit = 20,
    String sortBy = 'activity',
    String sortDir = 'desc',
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    final response = await _client.dio.post(
      'api/crm/contacts/search',
      data: {
        'user_id': userId,
        'query': query,
        'page': page,
        'pageLimit': pageLimit,
        'sort_by': sortBy,
        'sort_dir': sortDir,
      },
    );
    return _extractList(response.data, keys: ['contacts', 'data', 'items']);
  }

  /// Fuzzy name or phone match via `POST api/crm/contacts/match`.
  Future<List<Map<String, dynamic>>> matchContacts({
    required String query,
    int limit = 5,
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    final response = await _client.dio.post(
      'api/crm/contacts/match',
      data: {'user_id': userId, 'query': query, 'limit': limit},
    );
    return _extractNameMatches(response.data);
  }

  /// `POST api/crm/contacts/match` returns `{ contact, score, alternatives }`.
  List<Map<String, dynamic>> _extractNameMatches(dynamic data) {
    if (data is Map) {
      final root = Map<String, dynamic>.from(data);
      final matches = <Map<String, dynamic>>[];
      void add(dynamic value) {
        if (value is! Map) return;
        final map = Map<String, dynamic>.from(value);
        final nested = map['contact'];
        final contact = nested is Map ? Map<String, dynamic>.from(nested) : map;
        final id = '${contact['id'] ?? contact['contactId'] ?? contact['contact_id'] ?? ''}'.trim();
        if (id.isEmpty || matches.any((row) => '${row['id']}' == id)) return;
        matches.add(contact);
      }

      add(root['contact']);
      final alternatives = root['alternatives'];
      if (alternatives is List) {
        for (final item in alternatives) {
          add(item);
        }
      }
      if (matches.isNotEmpty) return matches;
    }
    return _extractList(
      data,
      keys: ['contacts', 'matches', 'data', 'items'],
    );
  }

  Future<List<Map<String, dynamic>>> getContacts({
    int page = 1,
    int limit = 30,
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    final response = await _client.dio.get(
      'api/crm/contacts',
      queryParameters: {'user_id': userId, 'page': page, 'limit': limit},
    );
    return _extractList(response.data, keys: ['contacts', 'data', 'items']);
  }

  Future<Map<String, dynamic>> addContact(Map<String, dynamic> body) async {
    final session = await _client.sessionRepository.load();
    final response = await _client.dio.post(
      'api/crm/contacts/add',
      data: {
        ...body,
        'user_id': session?.authUserId ?? session?.effectiveUserId,
      },
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  Future<Map<String, dynamic>> updateContact(Map<String, dynamic> body) async {
    final session = await _client.sessionRepository.load();
    final response = await _client.dio.post(
      'api/crm/contacts/update',
      data: {
        ...body,
        'user_id': session?.authUserId ?? session?.effectiveUserId,
      },
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  Future<Map<String, dynamic>> deleteContact(String contactId) async {
    final session = await _client.sessionRepository.load();
    final response = await _client.dio.post(
      'api/crm/contacts/delete',
      data: {
        'user_id': session?.authUserId ?? session?.effectiveUserId,
        'contactid': contactId,
        'contactId': contactId,
      },
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  /// Uploads a business-card image to `POST api/crm/business-cards/parse`.
  Future<Map<String, dynamic>> parseBusinessCard({
    required String imagePath,
    String? locale,
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    final path = imagePath.startsWith('file://')
        ? Uri.parse(imagePath).toFilePath()
        : imagePath;
    final fileName = path.split(RegExp(r'[\\/]')).last;
    final formData = FormData.fromMap({
      'user_id': userId?.toString() ?? '',
      'image': await MultipartFile.fromFile(
        path,
        filename: fileName.isEmpty ? 'business_card.jpg' : fileName,
      ),
      if (locale != null && locale.isNotEmpty) 'locale': locale,
    });
    final response = await _client.dio.post(
      'api/crm/business-cards/parse',
      data: formData,
    );
    final root = Map<String, dynamic>.from(response.data as Map);
    final success = root['success'];
    final status = root['status'];
    if (success == false || status == false) {
      final message = '${root['message'] ?? ''}'.trim();
      throw Exception(
        message.isEmpty ? 'Business card parsing failed' : message,
      );
    }
    final data = root['data'];
    if (data is Map) {
      return Map<String, dynamic>.from(data);
    }
    return root;
  }

  Future<List<Map<String, dynamic>>> getContactNotes(String contactId) async {
    final session = await _client.sessionRepository.load();
    final response = await _client.dio.get(
      'api/crm/contacts/notes/list',
      queryParameters: {
        'contactId': contactId,
        'contact_id': contactId,
        'user_id': session?.authUserId ?? session?.effectiveUserId,
      },
    );
    return _extractList(response.data, keys: ['notes', 'data', 'items']);
  }

  Future<void> createContactNote({
    required String contactId,
    required String body,
    DateTime? reminderAt,
    String? contactName,
  }) async {
    final session = await _client.sessionRepository.load();
    try {
      final response = await _client.dio.post(
        'api/crm/contacts/notes/add',
        data: {
          'contact_id': contactId,
          'contactId': contactId,
          'body': body,
          'urls': <String>[],
          'user_id': session?.authUserId ?? session?.effectiveUserId,
          if (reminderAt != null) 'reminder_at': _isoWithOffset(reminderAt),
          if (contactName != null && contactName.trim().isNotEmpty)
            'contact_name': contactName.trim(),
        },
      );
      final root = _asMap(response.data);
      if (root['success'] == false || root['status'] == false) {
        final message = '${root['message'] ?? ''}'.trim();
        throw Exception(message.isEmpty ? 'Could not save the note.' : message);
      }
    } on DioException catch (e) {
      throw Exception(
        _dioErrorMessage(e, fallback: 'Could not save the note.'),
      );
    }
  }

  /// Creates a contact reminder via `POST api/crm/contacts/reminders/add`.
  /// Pass [noteId] to attach it to a note that was just saved.
  Future<void> createContactReminder({
    required String contactId,
    required DateTime reminderAt,
    String? noteId,
    String? body,
    String? contactName,
  }) async {
    final session = await _client.sessionRepository.load();
    try {
      final response = await _client.dio.post(
        'api/crm/contacts/reminders/add',
        data: {
          'user_id': _jsonUserId(
            session?.authUserId ?? session?.effectiveUserId,
          ),
          'contactId': contactId,
          'contact_id': contactId,
          'reminder_at': _isoWithOffset(reminderAt),
          if (noteId != null && noteId.trim().isNotEmpty)
            'noteId': noteId.trim(),
          if (body != null && body.trim().isNotEmpty) 'body': body.trim(),
          if (contactName != null && contactName.trim().isNotEmpty)
            'contact_name': contactName.trim(),
        },
      );
      final root = _asMap(response.data);
      if (root['success'] == false || root['status'] == false) {
        final message = '${root['message'] ?? ''}'.trim();
        throw Exception(
          message.isEmpty ? 'Could not save the reminder.' : message,
        );
      }
    } on DioException catch (e) {
      throw Exception(
        _dioErrorMessage(e, fallback: 'Could not save the reminder.'),
      );
    }
  }

  /// Uploads an image/PDF/doc via `POST api/crm/contact-files/process`.
  /// Returns data including `voice_note_id` for later `media/process-batch`.
  Future<Map<String, dynamic>> processContactFile({
    required String contactId,
    required String filePath,
    String? fileName,
    String? mimeType,
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    if (userId == null || '$userId'.trim().isEmpty) {
      throw Exception('CRM user id not found');
    }
    final path = filePath.startsWith('file://')
        ? Uri.parse(filePath).toFilePath()
        : filePath;
    var name = (fileName ?? path.split(RegExp(r'[\\/]')).last).trim();
    final resolvedMime = _resolveUploadMime(
      mimeHint: mimeType,
      fileName: name.isEmpty ? path : name,
    );
    // Laravel validates by extension + Content-Type; ensure both are present.
    name = _ensureFileNameExtension(name, resolvedMime, fallback: 'attachment');
    try {
      final formData = FormData.fromMap({
        'contact_id': contactId,
        'user_id': userId.toString(),
        'file': await MultipartFile.fromFile(
          path,
          filename: name,
          contentType: MediaType.parse(resolvedMime),
        ),
      });
      final response = await _client.dio.post(
        'api/crm/contact-files/process',
        data: formData,
        options: Options(
          sendTimeout: const Duration(minutes: 3),
          receiveTimeout: const Duration(minutes: 3),
          // Let Dio set multipart boundary; do not force application/json.
          contentType: null,
        ),
      );
      final root = _asMap(response.data);
      if (root['success'] == false || root['status'] == false) {
        final message = '${root['message'] ?? ''}'.trim();
        throw Exception(message.isEmpty ? 'File upload failed' : message);
      }
      final data = root['data'];
      if (data is Map) return Map<String, dynamic>.from(data);
      return root;
    } on DioException catch (e) {
      throw Exception(_dioErrorMessage(e, fallback: 'File upload failed'));
    }
  }

  Future<List<Map<String, dynamic>>> getCallLogs({
    bool mine = true,
    int limit = 50,
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    final response = await _client.dio.post(
      'api/crm/call-logs/get',
      data: {'user_id': userId, 'mine': mine, 'limit': limit},
    );
    return _extractList(
      response.data,
      keys: ['call_logs', 'logs', 'data', 'items'],
    );
  }

  /// Uploads audio for AI transcription via `POST api/crm/voice-notes/process`.
  Future<Map<String, dynamic>> processVoiceNote({
    required String contactId,
    required String audioPath,
    String? existingNoteText,
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    if (userId == null || '$userId'.trim().isEmpty) {
      throw Exception('CRM user id not found');
    }
    final path = audioPath.startsWith('file://')
        ? Uri.parse(audioPath).toFilePath()
        : audioPath;
    var fileName = path.split(RegExp(r'[\\/]')).last;
    final mime = _resolveUploadMime(mimeHint: null, fileName: fileName);
    fileName = _ensureFileNameExtension(
      fileName,
      mime.startsWith('audio/') ? mime : 'audio/mp4',
      fallback: 'recording',
    );
    try {
      final formData = FormData.fromMap({
        'contact_id': contactId,
        'user_id': userId.toString(),
        'audio': await MultipartFile.fromFile(
          path,
          filename: fileName.isEmpty ? 'recording.m4a' : fileName,
          contentType: MediaType.parse(
            mime.startsWith('audio/') ? mime : 'audio/mp4',
          ),
        ),
        if (existingNoteText != null && existingNoteText.trim().isNotEmpty)
          'existing_note_text': existingNoteText.trim(),
      });
      final response = await _client.dio.post(
        'api/crm/voice-notes/process',
        data: formData,
        options: Options(
          sendTimeout: const Duration(minutes: 3),
          receiveTimeout: const Duration(minutes: 3),
          contentType: null,
        ),
      );
      final root = _asMap(response.data);
      if (root['success'] == false || root['status'] == false) {
        final message = '${root['message'] ?? ''}'.trim();
        throw Exception(
          message.isEmpty ? 'Voice note processing failed' : message,
        );
      }
      final data = root['data'];
      if (data is Map) return Map<String, dynamic>.from(data);
      return root;
    } on DioException catch (e) {
      throw Exception(
        _dioErrorMessage(e, fallback: 'Voice note processing failed'),
      );
    }
  }

  /// Attaches processed voice notes to the contact timeline.
  /// Returns the batch payload, including `crm_note_id` when the note was created.
  Future<Map<String, dynamic>> processMediaBatch({
    required String contactId,
    required List<String> voiceNoteIds,
    String? existingNoteText,
  }) async {
    final session = await _client.sessionRepository.load();
    final userId = session?.authUserId ?? session?.effectiveUserId;
    try {
      final response = await _client.dio.post(
        'api/crm/media/process-batch',
        data: {
          'user_id': userId?.toString() ?? '',
          'contact_id': contactId,
          'voice_note_ids': voiceNoteIds,
          if (existingNoteText != null && existingNoteText.trim().isNotEmpty)
            'existing_note_text': existingNoteText.trim(),
        },
      );
      final root = _asMap(response.data);
      if (root['success'] == false || root['status'] == false) {
        final message = '${root['message'] ?? ''}'.trim();
        throw Exception(message.isEmpty ? 'Could not save the note.' : message);
      }
      final data = root['data'];
      if (data is Map) return Map<String, dynamic>.from(data);
      return root;
    } on DioException catch (e) {
      throw Exception(
        _dioErrorMessage(e, fallback: 'Could not save the note.'),
      );
    }
  }

  Future<List<Map<String, dynamic>>> getTags() async {
    final session = await _client.sessionRepository.load();
    final response = await _client.dio.get(
      'api/crm/tags',
      queryParameters: {
        'user_id': session?.authUserId ?? session?.effectiveUserId,
      },
    );
    final names = _catalogTagNames(response.data);
    if (names.isNotEmpty) {
      return names.map((name) => {'name': name}).toList();
    }
    return _extractList(response.data, keys: ['tags', 'data', 'items']);
  }

  /// Replaces the contact's tags via `POST api/crm/contacts/add/tags`.
  Future<List<String>> updateContactTags({
    required String contactId,
    required List<String> tags,
  }) async {
    final session = await _client.sessionRepository.load();
    final rawUserId = session?.authUserId ?? session?.effectiveUserId;
    final userId = _jsonUserId(rawUserId);
    final normalized = _distinctTags(tags);
    try {
      final response = await _client.dio.post(
        'api/crm/contacts/add/tags',
        data: {
          // Same JSON type as the other CRM posts. A quoted user id is
          // rejected by integer validation, so the tag update never sticks.
          'user_id': userId,
          'contactid': contactId,
          'contactId': contactId,
          'tags': normalized,
        },
      );
      final root = _asMap(response.data);
      if (root['success'] == false || root['status'] == false) {
        final message = '${root['message'] ?? ''}'.trim();
        throw Exception(message.isEmpty ? 'Failed to update tags' : message);
      }
      final parsed = _responseTagNames(root);
      return parsed.isEmpty ? normalized : parsed;
    } on DioException catch (e) {
      throw Exception(_dioErrorMessage(e, fallback: 'Failed to update tags'));
    }
  }

  /// Local wall-clock time with its offset, e.g. `2026-10-03T10:00:00+05:30`.
  static String _isoWithOffset(DateTime value) {
    final local = value.toLocal();
    final offset = local.timeZoneOffset;
    final sign = offset.isNegative ? '-' : '+';
    final totalMinutes = offset.inMinutes.abs();
    final hours = (totalMinutes ~/ 60).toString().padLeft(2, '0');
    final minutes = (totalMinutes % 60).toString().padLeft(2, '0');
    String two(int n) => n.toString().padLeft(2, '0');
    final stamp =
        '${local.year}-${two(local.month)}-${two(local.day)}T${two(local.hour)}:${two(local.minute)}:${two(local.second)}';
    return '$stamp$sign$hours:$minutes';
  }

  /// Numeric ids are sent as JSON numbers so CRM validation accepts them.
  static Object? _jsonUserId(Object? raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    final text = '${raw ?? ''}'.trim();
    return int.tryParse(text) ?? (text.isEmpty ? null : text);
  }

  static List<String> _distinctTags(List<String> tags) {
    final out = <String>[];
    for (final tag in tags) {
      final trimmed = tag.trim();
      if (trimmed.isEmpty) continue;
      if (out.any(
        (existing) => existing.toLowerCase() == trimmed.toLowerCase(),
      )) {
        continue;
      }
      out.add(trimmed);
    }
    return out;
  }

  static List<String> _catalogTagNames(dynamic data) {
    if (data is List) return _tagLabels(data);
    if (data is! Map) return const [];
    final root = Map<String, dynamic>.from(data);
    for (final key in ['tags', 'items']) {
      final value = root[key];
      if (value is List) return _tagLabels(value);
    }
    final inner = root['data'];
    if (inner is List) return _tagLabels(inner);
    if (inner is Map) {
      final innerMap = Map<String, dynamic>.from(inner);
      for (final key in ['tags', 'items']) {
        final value = innerMap[key];
        if (value is List) return _tagLabels(value);
      }
    }
    return const [];
  }

  static List<String> _responseTagNames(Map<String, dynamic> root) {
    if (root.containsKey('tags')) return _tagLabels(root['tags']);
    final data = root['data'];
    if (data is Map) {
      final inner = Map<String, dynamic>.from(data);
      if (inner.containsKey('tags')) return _tagLabels(inner['tags']);
      final contact = inner['contact'];
      if (contact is Map && contact.containsKey('tags')) {
        return _tagLabels(contact['tags']);
      }
    }
    final contact = root['contact'];
    if (contact is Map && contact.containsKey('tags')) {
      return _tagLabels(contact['tags']);
    }
    return const [];
  }

  static List<String> _tagLabels(dynamic raw) {
    final tags = <String>[];
    void add(String label) {
      final trimmed = label.trim();
      if (trimmed.isEmpty || trimmed.toLowerCase() == 'null') return;
      if (tags.any(
        (existing) => existing.toLowerCase() == trimmed.toLowerCase(),
      )) {
        return;
      }
      tags.add(trimmed);
    }

    if (raw is String) {
      for (final part in raw.split(RegExp(r'[,|]'))) {
        add(part);
      }
      return tags;
    }
    if (raw is List) {
      for (final item in raw) {
        if (item is Map) {
          add(
            '${item['name'] ?? item['label'] ?? item['tag'] ?? item['title'] ?? item['value'] ?? ''}',
          );
        } else {
          add('$item');
        }
      }
    }
    return tags;
  }

  List<Map<String, dynamic>> _extractList(
    dynamic data, {
    required List<String> keys,
  }) {
    if (data is List) {
      return data.map((e) => Map<String, dynamic>.from(e as Map)).toList();
    }
    if (data is Map) {
      final root = Map<String, dynamic>.from(data);
      final inner = root['data'];
      if (inner is List) {
        return inner.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      if (inner is Map) {
        final innerMap = Map<String, dynamic>.from(inner);
        for (final key in keys) {
          final value = innerMap[key];
          if (value is List) {
            return value
                .map((e) => Map<String, dynamic>.from(e as Map))
                .toList();
          }
        }
      }
      for (final key in keys) {
        final value = root[key];
        if (value is List) {
          return value.map((e) => Map<String, dynamic>.from(e as Map)).toList();
        }
      }
    }
    return [];
  }

  static Map<String, dynamic> _asMap(dynamic data) {
    if (data is Map) return Map<String, dynamic>.from(data);
    return <String, dynamic>{};
  }

  static String _resolveUploadMime({
    String? mimeHint,
    required String fileName,
  }) {
    final hint = (mimeHint ?? '').trim().toLowerCase();
    if (hint.isNotEmpty &&
        hint.contains('/') &&
        !hint.endsWith('/*') &&
        hint != 'application/octet-stream' &&
        // image_picker sometimes builds image/jpg which Laravel rejects
        hint != 'image/jpg' &&
        hint != 'image/file') {
      return hint;
    }
    final ext = fileName.contains('.')
        ? fileName.split('.').last.toLowerCase()
        : '';
    return switch (ext) {
      'jpg' || 'jpeg' => 'image/jpeg',
      'png' => 'image/png',
      'gif' => 'image/gif',
      'webp' => 'image/webp',
      'bmp' => 'image/bmp',
      'heic' => 'image/heic',
      'heif' => 'image/heif',
      'pdf' => 'application/pdf',
      'doc' => 'application/msword',
      'docx' =>
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'm4a' || 'mp4' => 'audio/mp4',
      'mp3' => 'audio/mpeg',
      'wav' => 'audio/wav',
      'aac' => 'audio/aac',
      '3gp' => 'audio/3gpp',
      _ =>
        hint.startsWith('image/')
            ? 'image/jpeg'
            : (hint.isNotEmpty ? hint : 'application/octet-stream'),
    };
  }

  static String _ensureFileNameExtension(
    String name,
    String mime, {
    required String fallback,
  }) {
    final trimmed = name.trim();
    final hasExt =
        trimmed.contains('.') &&
        !trimmed.endsWith('.') &&
        trimmed.split('.').last.length <= 5;
    if (hasExt && trimmed.isNotEmpty) return trimmed;
    final ext = switch (mime.toLowerCase()) {
      'image/jpeg' || 'image/jpg' => 'jpg',
      'image/png' => 'png',
      'image/gif' => 'gif',
      'image/webp' => 'webp',
      'image/bmp' => 'bmp',
      'image/heic' => 'heic',
      'application/pdf' => 'pdf',
      'application/msword' => 'doc',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document' =>
        'docx',
      'audio/mp4' || 'audio/m4a' || 'audio/x-m4a' => 'm4a',
      'audio/mpeg' => 'mp3',
      'audio/wav' || 'audio/x-wav' => 'wav',
      'audio/aac' => 'aac',
      'audio/3gpp' => '3gp',
      _ => 'bin',
    };
    final base = trimmed.isEmpty ? fallback : trimmed;
    return '$base.$ext';
  }

  static String _dioErrorMessage(DioException e, {required String fallback}) {
    final data = e.response?.data;
    if (data is Map) {
      final message = '${data['message'] ?? ''}'.trim();
      if (message.isNotEmpty) return message;
      final errors = data['errors'];
      if (errors is Map && errors.isNotEmpty) {
        final first = errors.values.first;
        if (first is List && first.isNotEmpty) {
          return '${first.first}'.trim();
        }
        return '$first'.trim();
      }
    } else if (data is String && data.trim().isNotEmpty) {
      final trimmed = data.trim();
      if (trimmed.length < 200) return trimmed;
    }
    final status = e.response?.statusCode;
    if (status != null) return '$fallback (HTTP $status)';
    if ((e.message ?? '').trim().isNotEmpty) return e.message!.trim();
    return fallback;
  }
}
