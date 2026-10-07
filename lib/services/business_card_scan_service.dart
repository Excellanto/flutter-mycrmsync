import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/data/api_client.dart';
import 'package:mycrmsync/models/add_contact_form.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/services/business_card_text_parser.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:permission_handler/permission_handler.dart';

class BusinessCardScanResult {
  const BusinessCardScanResult({
    required this.form,
    this.warning,
  });

  final AddContactForm form;
  final String? warning;
}

/// Cross-platform business-card capture + parse.
///
/// - **Android**: ML Kit Document Scanner (fallback: camera/gallery), native API+OCR parse
/// - **iOS**: camera/gallery picker, CRM parse API, Vision OCR fallback
class BusinessCardScanService {
  BusinessCardScanService(this._api);

  final CrmApi _api;
  final _picker = ImagePicker();

  /// Capture only. Returns null when the user cancels.
  Future<String?> captureImage(BuildContext context) async {
    if (AppConfig.isAndroid) {
      try {
        final uri = await NativeBridges.launchBusinessCardScanner();
        if (uri != null && uri.isNotEmpty) return uri;
        // null => user cancelled document scanner
        return null;
      } on PlatformException catch (e) {
        debugPrint('ML Kit scanner unavailable: ${e.message}');
        // Fall through to camera/gallery.
      } catch (e) {
        debugPrint('ML Kit scanner failed: $e');
      }
    }

    if (!context.mounted) return null;
    final source = await _pickSource(context);
    if (source == null) return null;

    if (source == ImageSource.camera) {
      final cam = await Permission.camera.request();
      if (cam.isPermanentlyDenied || cam.isRestricted) {
        throw PlatformException(
          code: 'PERMISSION',
          message:
              'Camera access is turned off. Enable it in Settings to scan a business card.',
        );
      }
      if (!cam.isGranted) {
        throw PlatformException(
          code: 'PERMISSION',
          message: 'Camera permission is required to scan a business card.',
        );
      }
    }

    try {
      final photo = await _picker.pickImage(
        source: source,
        imageQuality: 92,
        maxWidth: 2500,
      );
      return photo?.path;
    } on PlatformException catch (e) {
      throw PlatformException(
        code: e.code,
        message: _imagePickerMessage(source, e),
      );
    }
  }

  String _imagePickerMessage(ImageSource source, PlatformException e) {
    final code = e.code.toLowerCase();
    final denied = code.contains('denied') ||
        code.contains('restricted') ||
        code.contains('permission');
    if (source == ImageSource.camera && (denied || code.contains('camera'))) {
      if (code.contains('unavailable') || code.contains('not_available')) {
        return 'The camera is not available on this device.';
      }
      if (denied) {
        return 'Camera access is turned off. Enable it in Settings to scan a business card.';
      }
    }
    if (denied) {
      return 'Photo library access is turned off. Enable it in Settings to choose a business card photo.';
    }
    return e.message ?? 'Could not open the camera or photo library.';
  }

  /// Parse a captured image path / content URI into contact fields.
  Future<BusinessCardScanResult> parseImage(String imagePath) async {
    if (AppConfig.isAndroid) {
      try {
        final map = await NativeBridges.parseBusinessCardImage(imagePath);
        final warning = map['warning']?.toString();
        return BusinessCardScanResult(
          form: AddContactForm.fromMap(map),
          warning: (warning != null && warning.isNotEmpty) ? warning : null,
        );
      } on PlatformException catch (e) {
        if (!_looksLikeFilePath(imagePath)) {
          return BusinessCardScanResult(
            form: const AddContactForm(),
            warning: e.message ?? 'Failed to read business card.',
          );
        }
      }
    }

    try {
      final data = await _api.parseBusinessCard(imagePath: imagePath);
      return BusinessCardScanResult(form: AddContactForm.fromMap(data));
    } catch (apiError) {
      final apiMessage = _friendlyError(apiError);
      try {
        final rawText = await NativeBridges.recognizeBusinessCardText(imagePath);
        if (rawText == null || rawText.trim().isEmpty) {
          return BusinessCardScanResult(
            form: const AddContactForm(),
            warning: '$apiMessage You can enter details manually.',
          );
        }
        return BusinessCardScanResult(
          form: BusinessCardTextParser.parse(rawText),
          warning: '$apiMessage Used on-device scan instead — please verify fields.',
        );
      } catch (_) {
        return BusinessCardScanResult(
          form: const AddContactForm(),
          warning: '$apiMessage You can enter details manually.',
        );
      }
    }
  }

  Future<ImageSource?> _pickSource(BuildContext context) {
    return showModalBottomSheet<ImageSource>(
      context: context,
      backgroundColor: AppColors.cardWhite,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 16, 20, 8),
              child: Text(
                'Scan business card',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textMain,
                ),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.photo_camera_outlined, color: AppColors.primaryBlue),
              title: const Text('Take photo'),
              onTap: () => Navigator.pop(ctx, ImageSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined, color: AppColors.primaryBlue),
              title: const Text('Choose from gallery'),
              onTap: () => Navigator.pop(ctx, ImageSource.gallery),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  bool _looksLikeFilePath(String path) {
    if (path.startsWith('content://')) return false;
    if (path.startsWith('file://')) return true;
    return path.contains('/') || path.contains('\\');
  }

  String _friendlyError(Object error) {
    final s = '$error';
    if (s.contains('SocketException') || s.contains('Timeout')) {
      return 'Could not reach the server.';
    }
    final match = RegExp(r'(?:Exception|Error):\s*(.+)').firstMatch(s);
    return match?.group(1)?.trim() ?? 'Business card parsing failed.';
  }
}
