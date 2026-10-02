import 'package:flutter/foundation.dart';

class AppConfig {
  static const String apiBaseUrl = 'https://app.mycrmsync.in/';

  static bool get isAndroid =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static bool get isIos =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;
}
