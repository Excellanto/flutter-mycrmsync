# MyCrmSync iOS setup

This file is the handoff for building MyCrmSync on a Mac or Codemagic. Android behavior is unchanged.

## 1. iOS minimum version

**iOS 15.0**

Flutter 3.41.9’s own floor is iOS 13.0. The plugins in this project declare iOS 9–13. iOS 14 would build, but it is not the target used here. iOS 15 is the deployment target set in:

- `ios/Runner.xcodeproj/project.pbxproj` (`IPHONEOS_DEPLOYMENT_TARGET`)
- `ios/Podfile` (`platform :ios, '15.0'` and the `post_install` hook)

Do not lower this to iOS 14 unless you also change both of those places.

## 2. Bundle ID

`com.excellanto.mycrmsync`

This matches the Android `applicationId`. It is already set on the Runner target for Debug, Profile, and Release. Confirm this exact identifier is registered to your Apple Developer team before you submit. The test target uses `com.excellanto.mycrmsync.RunnerTests` and is not the App Store id.

Android `applicationId` was not changed.

## 3. App name

- Home screen name: **MyCrmSync** (`CFBundleDisplayName` in `ios/Runner/Info.plist`)
- Flutter package name: `mycrmsync` (unchanged)

## 4. Required permissions

Only these are requested:

| Permission | Why | Dart usage |
|---|---|---|
| Microphone | Voice notes on a contact | `Permission.microphone` |
| Camera | Photograph a business card | `Permission.camera` |
| Photo library | Attach a note image, or pick a business-card photo | `image_picker` gallery (system picker; no `Permission.photos` call) |

Not requested, because the Flutter code does not use them: contacts, Bluetooth, location, speech recognition, notifications, photo-library *add*. Android still has its own contact, phone, and notification permissions. Those stay on Android only.

## 5. Info.plist entries

`ios/Runner/Info.plist` includes:

- `CFBundleDisplayName` = MyCrmSync
- `NSMicrophoneUsageDescription`
- `NSCameraUsageDescription`
- `NSPhotoLibraryUsageDescription`
- `LSApplicationQueriesSchemes`: `tel`, `sms`, `mailto`
- `ITSAppUsesNonExemptEncryption` = false (HTTPS plus Apple Keychain only)
- Portrait and landscape orientations, same as the Flutter template
- No `UIBackgroundModes`
- App Transport Security is **not** disabled

WhatsApp chat links use `https://wa.me/...`. That does not need a `whatsapp` URL scheme.

## 6. Podfile

`ios/Podfile` is the Flutter 3.41 CocoaPods template, plus:

- platform iOS 15.0
- `permission_handler` macros **only** for camera and microphone:

```ruby
PERMISSION_CAMERA=1
PERMISSION_MICROPHONE=1
```

Every other permission macro stays at the plugin default of 0. Do not turn the rest on. Apple’s static analyzer treats an enabled macro as a permission the app uses, and then requires a matching usage string.

`ios/Flutter/Debug.xcconfig` and `Release.xcconfig` optionally include the CocoaPods xcconfig files. Profile builds use the Release xcconfig.

On a Mac, after `flutter pub get`:

```bash
cd ios
pod install
```

`flutter build ipa` / `flutter build ios` runs that for you when CocoaPods is installed.

## 7. iOS native code

| File | Role |
|---|---|
| `ios/Runner/AppDelegate.swift` | Registers Flutter plugins. Android-only channels return false, empty lists, or “unsupported”. They do not invent call or WhatsApp events. |
| `ios/Runner/BusinessCardChannel.swift` | Apple Vision OCR for a business-card image path. Document scanning itself uses `image_picker`. |
| `ios/Runner/SceneDelegate.swift` | Flutter UIScene delegate. Do not replace this with the old pre-UIScene `AppDelegate` pattern. |

Channel names match Android (`com.excellanto.mycrmsync/...`). Android `NativeBridgePlugin.kt` was not modified.

## 8. Android-only functionality

Kept as-is under `android/`. Flutter reaches it through `lib/platform/native_bridges.dart`, which returns immediately on iOS.

| Android piece | What it does |
|---|---|
| `CallReceiver` | `PHONE_STATE` broadcast. Reads `TelephonyManager` state for ringing, off-hook, and idle. |
| `PhoneCallMonitorService` | Foreground service that keeps call watching alive. |
| `OutgoingCallTracker` | Marks a number the user just dialed from the app. |
| `SystemCallLogReader` / call-log channel | Reads the device call log (`READ_CALL_LOG`). |
| `CallNoteSummaryActivity` + overlay service | Post-call note UI over the dialer (`SYSTEM_ALERT_WINDOW`). |
| `WhatsAppCallAccessibilityService` | Accessibility service that detects WhatsApp call UI. It does not read WhatsApp message contents. |
| `PhoneCallMonitorBootReceiver` | Restarts monitoring after boot. |
| WorkManager workers | `CallSyncWorker`, `WhatsAppCallSyncWorker`, `OemRecordingSyncWorker`. |
| OEM recording discovery | Finds handset call-recording files and matches them to calls. |
| `READ_CONTACTS` | Native contact matching for calls. Flutter does not call `Permission.contacts`. |

`lib/services/call_platform.dart` is the small Dart boundary for placing a call:

- Android: existing native `ACTION_CALL`, then `tel:` if that fails
- iOS: `tel:` only, through the Phone app

## 9. iOS limitations

These cannot be reproduced, and the app does not fake them:

- No incoming or outgoing call detection
- No device call log
- No call recording, and no reading of OEM recording folders
- No WhatsApp call detection, accessibility service, or notification listener
- No overlay on top of the Phone app or WhatsApp
- No background or foreground service for calls
- No boot receiver

What still works on iOS:

- Login, session, and the CRM screens (dashboard, tasks, contacts, notes, call history from the API)
- Place a call, send an SMS, send email, open a `https://wa.me` chat
- Record a voice note in the foreground (AAC) and play audio
- Pick photos, files (`pdf`, `doc`, `docx`, `jpg`, `jpeg`, `png`, and voice files)
- Scan a business card with the camera or photo library, then parse it through the CRM API, with Vision OCR as fallback
- API base URL `https://app.mycrmsync.in/` (HTTPS, so ATS stays on)

The dashboard, call log, and drawer already say device sync and WhatsApp tracking are Android-only. The WhatsApp setup route does not run the Android service on iOS.

## 10. Apple Developer requirements

1. An Apple Developer Program membership (paid).
2. App ID `com.excellanto.mycrmsync` with Push **off** (the app does not use push).
3. Capabilities: none beyond the default. Keychain access for `flutter_secure_storage` does **not** need the Keychain Sharing capability on iOS.
4. No associated domains, no background modes, no CallKit, no PushKit.

## 11. App Store Connect requirements

1. Create the app with bundle ID `com.excellanto.mycrmsync` and name MyCrmSync.
2. Privacy questionnaire, based on what the binary actually does:
   - Contact info the user types or that comes from your CRM API
   - Photos / camera (business cards and note images)
   - Microphone (voice notes)
   - Audio data uploaded to your server when the user saves a voice note
   - Do **not** declare call-log access, WhatsApp message access, or tracking
3. Export compliance: `ITSAppUsesNonExemptEncryption` is false, so the standard “uses only exempt encryption” answer applies (HTTPS and Keychain).
4. Age rating and support URL are yours to fill in. This repo does not contain them.
5. Replace the app icon before review. See below.

## 12. Codemagic

Use a macOS instance with Xcode that can deploy to iOS 15 (current Xcode stable is fine).

Suggested workflow settings:

- Flutter **3.41.9** (Dart 3.11.5), stable channel
- `flutter pub get`
- `flutter build ipa --release` (or `flutter build ios --release --no-codesign` only for an unsigned check)
- CocoaPods is installed on Codemagic images. The first iOS build runs `pod install` from `ios/Podfile`.
- Do not run `flutter create`. It would overwrite `AppDelegate.swift` and `BusinessCardChannel.swift`.

A typical Codemagic script:

```bash
flutter pub get
flutter build ipa --release \
  --export-options-plist=/Users/builder/export_options.plist
```

Codemagic’s Flutter workflow can generate the export options plist from the App Store Connect integration. You do not need a checked-in plist for that.

## 13. Signing

Windows cannot sign an iOS app. On Codemagic (or a Mac):

1. Create an Apple Distribution certificate.
2. Create an App Store provisioning profile for `com.excellanto.mycrmsync`.
3. In Codemagic, connect the Apple Developer account and let it fetch the certificate and profile, or upload them as encrypted variables.
4. The Xcode project uses automatic signing style on the test target and `iPhone Developer` as the device identity placeholder. Codemagic’s code-signing step overrides this for the archive. Set the team on the Runner target in Xcode the first time a human opens the project, or set `DEVELOPMENT_TEAM` in the Codemagic workflow.

Do not commit certificates or `.p12` files.

## 14. TestFlight

1. Archive with `flutter build ipa --release` on Codemagic.
2. Upload the `.ipa` with Codemagic’s App Store Connect publishing step, or with Transporter / `xcrun altool`.
3. In App Store Connect, wait for processing.
4. Answer export compliance if Apple still asks (the plist flag usually skips it).
5. Add internal testers. External testers need a short Beta App Review.
6. Install from the TestFlight app on an iPhone running iOS 15 or later.

## 15. Manual steps still required on a Mac or Codemagic

1. Register bundle ID `com.excellanto.mycrmsync` and confirm you own it.
2. Create distribution signing assets.
3. Run the first `flutter build ipa` so CocoaPods generates `ios/Pods` and updates the workspace. Those folders are gitignored.
4. Replace the app icon. The catalog at `ios/Runner/Assets.xcassets/AppIcon.appiconset` currently contains the **default Flutter logo**, which is enough for a compile. `assets/logo.png` is a shield on a large black canvas. Do not stretch that file into the icon slots; it will show as a tiny mark on black, and App Store icons cannot be transparent.

   Provide one **1024×1024 PNG, no alpha channel**, with the shield filling the square and with padding inside Apple’s icon mask. Then regenerate every slot listed in `Contents.json` (20, 29, 40, 60, 76, 83.5, and 1024 points at the scales already named there) or let Xcode 14+ generate them from the 1024 image.

5. Smoke-test on a real iPhone: login, contacts, `tel:`, WhatsApp link, business-card camera and library, voice note, file attach, and confirm call-log sync controls stay Android-only.
6. `flutter build ios` cannot run on this Windows machine. That is a host limitation, not a project error.

## Version

`pubspec.yaml` stays `1.0.0+1`. iOS reads that as `CFBundleShortVersionString` 1.0.0 and `CFBundleVersion` 1 through `FLUTTER_BUILD_NAME` and `FLUTTER_BUILD_NUMBER`.
