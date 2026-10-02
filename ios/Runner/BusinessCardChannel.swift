import Flutter
import UIKit
import Vision

/// Business-card OCR via Apple Vision (API parse is done in Flutter).
enum BusinessCardChannel {
  static let name = "com.excellanto.mycrmsync/business_card"

  static func register(with messenger: FlutterBinaryMessenger) {
    FlutterMethodChannel(name: name, binaryMessenger: messenger)
      .setMethodCallHandler { call, result in
        switch call.method {
        case "launchScanner":
          // iOS uses Flutter image_picker (camera / gallery).
          result(nil)
        case "recognizeText":
          guard let args = call.arguments as? [String: Any],
                let uri = args["uri"] as? String,
                !uri.isEmpty
          else {
            result(
              FlutterError(code: "ARGS", message: "uri required", details: nil)
            )
            return
          }
          recognizeText(at: uri, result: result)
        case "parseImage":
          result(
            FlutterError(
              code: "UNSUPPORTED",
              message: "Use CRM parse API + recognizeText on iOS",
              details: nil
            )
          )
        default:
          result(FlutterMethodNotImplemented)
        }
      }
  }

  private static func recognizeText(at pathOrUri: String, result: @escaping FlutterResult) {
    DispatchQueue.global(qos: .userInitiated).async {
      do {
        let image = try loadUIImage(pathOrUri)
        guard let cgImage = image.cgImage else {
          DispatchQueue.main.async {
            result(
              FlutterError(
                code: "IMAGE",
                message: "Could not read business card image",
                details: nil
              )
            )
          }
          return
        }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        if #available(iOS 16.0, *) {
          request.revision = VNRecognizeTextRequestRevision3
        }

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try handler.perform([request])

        let observations = request.results ?? []
        let lines = observations.compactMap { $0.topCandidates(1).first?.string }
        let text = lines.joined(separator: "\n")

        DispatchQueue.main.async {
          result(text)
        }
      } catch {
        DispatchQueue.main.async {
          result(
            FlutterError(
              code: "OCR",
              message: error.localizedDescription,
              details: nil
            )
          )
        }
      }
    }
  }

  private static func loadUIImage(_ pathOrUri: String) throws -> UIImage {
    if pathOrUri.hasPrefix("file://"),
       let url = URL(string: pathOrUri),
       let data = try? Data(contentsOf: url),
       let image = UIImage(data: data)
    {
      return image
    }

    if FileManager.default.fileExists(atPath: pathOrUri),
       let image = UIImage(contentsOfFile: pathOrUri)
    {
      return image
    }

    if let url = URL(string: pathOrUri),
       let data = try? Data(contentsOf: url),
       let image = UIImage(data: data)
    {
      return image
    }

    throw NSError(
      domain: "BusinessCardChannel",
      code: 1,
      userInfo: [NSLocalizedDescriptionKey: "Could not load image from path"]
    )
  }
}
