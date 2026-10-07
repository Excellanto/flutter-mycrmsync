import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens [uri] in an external app. Returns false when iOS/Android has no handler
/// or the system refuses the launch. Does not throw.
Future<bool> openExternalUri(Uri uri) async {
  try {
    if (!await canLaunchUrl(uri)) return false;
    return await launchUrl(uri, mode: LaunchMode.externalApplication);
  } on PlatformException {
    return false;
  } catch (_) {
    return false;
  }
}

Future<void> openExternalLink(
  BuildContext context,
  Uri uri, {
  required String failureMessage,
}) async {
  final opened = await openExternalUri(uri);
  if (!opened && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(failureMessage)),
    );
  }
}
