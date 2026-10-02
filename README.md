# MyCrmSync (Flutter hybrid)

Flutter UI + CRM/auth layer with **existing Android Kotlin services** kept for call detection, overlays, WhatsApp accessibility, WorkManager sync, Room outbox, and OEM recordings.

## Run

```bash
cd mycrmsync_flutter
flutter pub get
flutter run -d android   # full native features
flutter run -d ios       # CRM/auth UI only (no WhatsApp / OEM recordings)
```

## Package id

`com.excellanto.mycrmsync` (same as the legacy Compose app — upgrade-friendly prefs/Room).

## Architecture

| Layer | Location |
|-------|----------|
| Flutter UI / APIs | `lib/` |
| Android native services | `android/app/src/main/java/com/excellanto/mycrmsync/` |
| Platform channels | `NativeBridgePlugin.kt` + `lib/platform/native_bridges.dart` |
| iOS no-op stubs | `ios/Runner/AppDelegate.swift` |

## iOS limitations

- No accessibility / WhatsApp call capture
- No OEM call-recording discovery
- Call logs / dashboard use CRM API data only

## Legacy Android module

The original Compose app remains under repo-root `app/` until Play cutover. Prefer developing and shipping from `mycrmsync_flutter/`.
