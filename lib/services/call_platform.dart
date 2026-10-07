import 'package:flutter/services.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/services/external_links.dart';

/// Device call monitoring stays on Android. iOS can only hand a number to
/// the Phone app. Apple does not provide call-log, telephony, or WhatsApp
/// notification APIs that match the Android implementation.
abstract class CallPlatform {
  bool get supportsDeviceCallLog;
  bool get supportsWhatsAppCallDetection;
  bool get supportsOemCallRecordingSync;

  /// Opens the system phone UI for [phone]. Returns false when no handler exists.
  Future<bool> placeCall(String phone);
}

class AndroidCallPlatform implements CallPlatform {
  const AndroidCallPlatform();

  @override
  bool get supportsDeviceCallLog => true;

  @override
  bool get supportsWhatsAppCallDetection => true;

  @override
  bool get supportsOemCallRecordingSync => true;

  @override
  Future<bool> placeCall(String phone) async {
    final trimmed = phone.trim();
    if (trimmed.isEmpty) return false;
    try {
      await NativeBridges.placeCall(trimmed);
      return true;
    } on PlatformException {
      return openExternalUri(Uri(scheme: 'tel', path: trimmed));
    } catch (_) {
      return openExternalUri(Uri(scheme: 'tel', path: trimmed));
    }
  }
}

class IosCallPlatform implements CallPlatform {
  const IosCallPlatform();

  @override
  bool get supportsDeviceCallLog => false;

  @override
  bool get supportsWhatsAppCallDetection => false;

  @override
  bool get supportsOemCallRecordingSync => false;

  @override
  Future<bool> placeCall(String phone) async {
    final trimmed = phone.trim();
    if (trimmed.isEmpty) return false;
    return openExternalUri(Uri(scheme: 'tel', path: trimmed));
  }
}

CallPlatform get callPlatform => AppConfig.isAndroid
    ? const AndroidCallPlatform()
    : const IosCallPlatform();
