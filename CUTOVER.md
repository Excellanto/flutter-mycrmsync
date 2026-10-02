# Flutter hybrid cutover notes

## Built artifacts

- Debug APK: `mycrmsync_flutter/build/app/outputs/flutter-apk/app-debug.apk`
- Application id: `com.excellanto.mycrmsync` (same as legacy Compose app)

## Feature parity checklist

- [x] Splash → connect → OTP → dashboard
- [x] Session mirrored to Android `crm_prefs` for WorkManager
- [x] Dashboard KPIs from CRM call-logs API + pull-to-refresh sync
- [x] Contacts search/list/add + dial + notes
- [x] Call logs me/all + Android OEM sync button
- [x] WhatsApp setup (Android only; hidden on iOS)
- [x] WhatsApp post-call EventChannel dialog (Android)
- [x] Overlay permission prompt (Android); overlay UI stays native
- [x] iOS stubs for all MethodChannels + empty post-call stream
- [x] iOS empty-state copy for Android-only features
- [x] Legacy Compose `MainActivity` marked deprecated; Flutter host is primary

## Store notes

- Android: full call sync, WhatsApp accessibility, OEM recordings
- iOS: CRM/auth/contacts/notes/call-history API only
