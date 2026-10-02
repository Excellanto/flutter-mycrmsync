import 'package:flutter/services.dart';
import 'package:mycrmsync/core/config.dart';

/// Facades for Android native services. iOS methods no-op / return defaults.
class NativeBridges {
  NativeBridges._();

  static const _session = MethodChannel('com.excellanto.mycrmsync/session');
  static const _permissions = MethodChannel('com.excellanto.mycrmsync/permissions');
  static const _calls = MethodChannel('com.excellanto.mycrmsync/calls');
  static const _sync = MethodChannel('com.excellanto.mycrmsync/sync');
  static const _whatsapp = MethodChannel('com.excellanto.mycrmsync/whatsapp');
  static const _calllogRead = MethodChannel('com.excellanto.mycrmsync/calllog_read');
  static const _recordings = MethodChannel('com.excellanto.mycrmsync/recordings');
  static const _launch = MethodChannel('com.excellanto.mycrmsync/launch');
  static const _businessCard =
      MethodChannel('com.excellanto.mycrmsync/business_card');
  static const _postCallEvents =
      EventChannel('com.excellanto.mycrmsync/whatsapp_post_call');

  static Future<void> setSession(Map<String, dynamic> session) async {
    if (!AppConfig.isAndroid) return;
    await _session.invokeMethod('setSession', session);
  }

  static Future<void> clearSession() async {
    if (!AppConfig.isAndroid) return;
    await _session.invokeMethod('clearSession');
  }

  static Future<Map<String, dynamic>?> getNativeSession() async {
    if (!AppConfig.isAndroid) return null;
    final result = await _session.invokeMethod<Map>('getNativeSession');
    return result?.map((k, v) => MapEntry(k.toString(), v));
  }

  static Future<void> requestRuntimePermissions() async {
    if (!AppConfig.isAndroid) return;
    await _permissions.invokeMethod('requestRuntimePermissions');
  }

  static Future<bool> canDrawOverlays() async {
    if (!AppConfig.isAndroid) return false;
    return await _permissions.invokeMethod<bool>('canDrawOverlays') ?? false;
  }

  static Future<void> openOverlaySettings() async {
    if (!AppConfig.isAndroid) return;
    await _permissions.invokeMethod('openOverlaySettings');
  }

  static Future<void> placeCall(String phone) async {
    if (AppConfig.isAndroid) {
      await _calls.invokeMethod('placeCall', {'phone': phone});
      return;
    }
    // iOS: tel: via url_launcher is handled by caller.
  }

  static Future<void> setPendingOutgoing(String phone) async {
    if (!AppConfig.isAndroid) return;
    await _calls.invokeMethod('setPendingOutgoing', {'phone': phone});
  }

  static Future<void> enqueueNow() async {
    if (!AppConfig.isAndroid) return;
    await _sync.invokeMethod('enqueueNow');
  }

  static Future<void> schedulePeriodicIfLoggedIn() async {
    if (!AppConfig.isAndroid) return;
    await _sync.invokeMethod('schedulePeriodicIfLoggedIn');
  }

  static Future<void> enqueueOemRecordingSyncNow() async {
    if (!AppConfig.isAndroid) return;
    await _sync.invokeMethod('enqueueOemRecordingSyncNow');
  }

  static Future<bool> isAccessibilityEnabled() async {
    if (!AppConfig.isAndroid) return false;
    return await _whatsapp.invokeMethod<bool>('isAccessibilityEnabled') ?? false;
  }

  static Future<void> openAccessibilitySettings() async {
    if (!AppConfig.isAndroid) return;
    await _whatsapp.invokeMethod('openAccessibilitySettings');
  }

  static Future<bool> shouldShowSetupPrompt() async {
    if (!AppConfig.isAndroid) return false;
    return await _whatsapp.invokeMethod<bool>('shouldShowSetupPrompt') ?? false;
  }

  static Future<bool> rememberIfEnabled() async {
    if (!AppConfig.isAndroid) return false;
    return await _whatsapp.invokeMethod<bool>('rememberIfEnabled') ?? false;
  }

  static Future<bool> isTrackingEnabled() async {
    if (!AppConfig.isAndroid) return false;
    return await _whatsapp.invokeMethod<bool>('isTrackingEnabled') ?? false;
  }

  static Future<void> setTrackingEnabled(bool enabled) async {
    if (!AppConfig.isAndroid) return;
    await _whatsapp.invokeMethod('setTrackingEnabled', {'enabled': enabled});
  }

  static Future<Map<String, dynamic>> getPendingCounts() async {
    if (!AppConfig.isAndroid) {
      return {'pendingCallRegistrations': 0, 'pendingWhatsAppCalls': 0};
    }
    final result = await _calllogRead.invokeMethod<Map>('getPendingCounts');
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  static Future<List<Map<String, dynamic>>> listLocalWithRecordings({
    int limit = 200,
  }) async {
    if (!AppConfig.isAndroid) return const [];
    final result =
        await _calllogRead.invokeMethod<List>('listLocalWithRecordings', {
      'limit': limit,
    });
    if (result == null) return const [];
    return result
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  static Future<List<String>> listNoteUploadedFingerprints() async {
    if (!AppConfig.isAndroid) return const [];
    final result =
        await _calllogRead.invokeMethod<List>('listNoteUploadedFingerprints');
    if (result == null) return const [];
    return result.map((e) => '$e').where((e) => e.isNotEmpty).toList();
  }

  static Future<void> markCallNoteSaved({
    required String fingerprint,
    required bool isWhatsApp,
  }) async {
    if (!AppConfig.isAndroid) return;
    final fp = fingerprint.trim();
    if (fp.isEmpty) return;
    await _calllogRead.invokeMethod('markCallNoteSaved', {
      'fingerprint': fp,
      'isWhatsApp': isWhatsApp,
    });
  }

  static Future<List<Map<String, dynamic>>> listRecentWhatsAppCalls({
    int limit = 50,
  }) async {
    if (!AppConfig.isAndroid) return const [];
    final result = await _calllogRead.invokeMethod<List>('listRecentWhatsApp', {
      'limit': limit,
    });
    if (result == null) return const [];
    return result
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  static Future<void> syncOemNow() async {
    if (!AppConfig.isAndroid) return;
    await _recordings.invokeMethod('syncOemNow');
  }

  /// Resolves content:// / file paths to a local path or returns http(s) unchanged.
  static Future<String?> resolvePlayablePath(String uri) async {
    if (!AppConfig.isAndroid) return uri;
    return _recordings.invokeMethod<String>('resolvePlayablePath', {'uri': uri});
  }

  static Future<String?> consumePostCallFingerprint() async {
    if (!AppConfig.isAndroid) return null;
    return _launch.invokeMethod<String>('consumePostCallFingerprint');
  }

  /// Opens ML Kit Document Scanner. Returns image URI string, or null if cancelled.
  static Future<String?> launchBusinessCardScanner() async {
    if (!AppConfig.isAndroid) return null;
    return _businessCard.invokeMethod<String>('launchScanner');
  }

  /// Parses a scanned card image (API first, on-device OCR fallback).
  /// Returns form fields plus optional `warning`.
  static Future<Map<String, dynamic>> parseBusinessCardImage(String uri) async {
    if (!AppConfig.isAndroid) {
      throw PlatformException(
        code: 'UNSUPPORTED',
        message: 'Native parseImage is Android-only; use CRM API + recognizeText on iOS',
      );
    }
    final result = await _businessCard.invokeMethod<Map>('parseImage', {'uri': uri});
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  /// On-device OCR for a business-card image path/URI. Returns raw text.
  static Future<String?> recognizeBusinessCardText(String uri) async {
    return _businessCard.invokeMethod<String>('recognizeText', {'uri': uri});
  }

  static Stream<Map<String, dynamic>> whatsAppPostCallEvents() {
    if (!AppConfig.isAndroid) return const Stream.empty();
    return _postCallEvents.receiveBroadcastStream().map((event) {
      final map = Map<String, dynamic>.from(event as Map);
      return map;
    });
  }
}
