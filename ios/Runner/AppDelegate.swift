import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let messenger = engineBridge.applicationRegistrar.messenger()
    registerNoOpChannels(with: messenger)
    BusinessCardChannel.register(with: messenger)
  }

  /// iOS stubs: same channel names as Android; Android-only features return false / empty.
  private func registerNoOpChannels(with messenger: FlutterBinaryMessenger) {
    let channels = [
      "com.excellanto.mycrmsync/session",
      "com.excellanto.mycrmsync/permissions",
      "com.excellanto.mycrmsync/calls",
      "com.excellanto.mycrmsync/sync",
      "com.excellanto.mycrmsync/whatsapp",
      "com.excellanto.mycrmsync/calllog_read",
      "com.excellanto.mycrmsync/recordings",
      "com.excellanto.mycrmsync/launch",
    ]

    for name in channels {
      FlutterMethodChannel(name: name, binaryMessenger: messenger)
        .setMethodCallHandler { call, result in
          switch call.method {
          case "setSession", "clearSession", "requestRuntimePermissions",
               "openOverlaySettings", "openAccessibilitySettings",
               "enqueueNow", "schedulePeriodicIfLoggedIn", "enqueueAfterCall",
               "enqueueOemRecordingSyncNow", "syncOemNow",
               "setPendingOutgoing", "setTrackingEnabled",
               "setPostCallNotificationsEnabled":
            result(nil)
          case "canDrawOverlays", "isAccessibilityEnabled", "shouldShowSetupPrompt",
               "rememberIfEnabled", "isTrackingEnabled",
               "isPostCallNotificationsEnabled":
            result(false)
          case "getNativeSession":
            result([:] as [String: Any])
          case "getPendingCounts":
            result([
              "pendingCallRegistrations": 0,
              "pendingWhatsAppCalls": 0,
            ])
          case "listRecentWhatsApp", "listLocalWithRecordings", "consumePostCallFingerprint":
            result(nil)
          case "resolvePlayablePath":
            result(nil)
          case "placeCall":
            result(
              FlutterError(
                code: "UNSUPPORTED",
                message: "Use url_launcher tel: on iOS",
                details: nil
              )
            )
          default:
            result(FlutterMethodNotImplemented)
          }
        }
    }

    FlutterEventChannel(
      name: "com.excellanto.mycrmsync/whatsapp_post_call",
      binaryMessenger: messenger
    ).setStreamHandler(EmptyStreamHandler())
  }
}

private class EmptyStreamHandler: NSObject, FlutterStreamHandler {
  func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink)
    -> FlutterError?
  {
    return nil
  }

  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    return nil
  }
}
