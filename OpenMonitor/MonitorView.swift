import SwiftUI
import AVFoundation
import UIKit

final class DeviceManager: ObservableObject {
    @Published var devices: [AVCaptureDevice] = []

    init() {
        fetchDevices()
    }

    func fetchDevices() {
        let deviceTypes: [AVCaptureDevice.DeviceType] = [
            .external,
            .builtInDualCamera,
            .builtInDualWideCamera,
            .builtInTelephotoCamera,
            .builtInTripleCamera,
            .builtInUltraWideCamera,
            .builtInWideAngleCamera,
            .continuityCamera
        ]
        let discoverySession = AVCaptureDevice.DiscoverySession(deviceTypes: deviceTypes, mediaType: .video, position: .unspecified)
        devices = discoverySession.devices
    }
}

struct MonitorView: View {
    @StateObject private var deviceManager = DeviceManager()
    @State private var selectedInputID: String?
    @State private var captureSession: AVCaptureSession?
    @State private var isStarting = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if deviceManager.devices.isEmpty {
                    ContentUnavailableView("No local cameras", systemImage: "camera", description: Text("Connect a compatible camera or capture device."))
                } else {
                    Picker("Input", selection: Binding(get: {
                        selectedInputID ?? deviceManager.devices.first?.uniqueID ?? ""
                    }, set: { selectedInputID = $0 })) {
                        ForEach(deviceManager.devices, id: \.uniqueID) { device in
                            Text(device.localizedName).tag(device.uniqueID)
                        }
                    }
                    .pickerStyle(.menu)

                    Button(isStarting ? "Starting…" : "Start Local Preview") {
                        startPreview()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isStarting)

                    if let session = captureSession {
                        LocalCapturePreview(session: session)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(alignment: .topLeading) {
                                Text("Local input")
                                    .font(.caption.weight(.semibold))
                                    .padding(8)
                                    .background(.black.opacity(0.45), in: Capsule())
                                    .foregroundStyle(.white)
                                    .padding()
                            }
                    } else {
                        ContentUnavailableView("No preview running", systemImage: "video", description: Text("Choose a camera input to start monitoring."))
                    }
                }
            }
            .padding()
            .navigationTitle("Local Monitor")
            .task {
                if selectedInputID == nil {
                    selectedInputID = deviceManager.devices.first?.uniqueID
                }
            }
            .alert("Camera Error", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            ), actions: {
                Button("OK") { errorMessage = nil }
            }, message: {
                Text(errorMessage ?? "")
            })
            .onDisappear {
                captureSession?.stopRunning()
            }
        }
    }

    private func startPreview() {
        guard let selectedInputID,
              let device = deviceManager.devices.first(where: { $0.uniqueID == selectedInputID }) else {
            errorMessage = "No camera input is selected."
            return
        }

        isStarting = true
        let session = AVCaptureSession()

        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) {
                session.addInput(input)
            } else {
                errorMessage = "Selected camera cannot be attached to a session."
                isStarting = false
                return
            }
        } catch {
            errorMessage = "Failed to create camera input: \(error.localizedDescription)"
            isStarting = false
            return
        }

        captureSession = session
        device.requestAccessIfNeeded { granted in
            guard granted else {
                DispatchQueue.main.async {
                    self.errorMessage = "Camera permission is required for the local preview."
                    self.isStarting = false
                }
                return
            }

            DispatchQueue.global(qos: .userInitiated).async {
                session.startRunning()
                DispatchQueue.main.async {
                    self.isStarting = false
                }
            }
        }
    }
}

private extension AVCaptureDevice {
    func requestAccessIfNeeded(completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
        default:
            completion(false)
        }
    }
}

struct LocalCapturePreview: UIViewControllerRepresentable {
    let session: AVCaptureSession

    func makeUIViewController(context: Context) -> PreviewViewController {
        let controller = PreviewViewController()
        controller.captureSession = session
        return controller
    }

    func updateUIViewController(_ uiViewController: PreviewViewController, context: Context) {
        uiViewController.captureSession = session
        uiViewController.refreshPreview()
    }
}

final class PreviewViewController: UIViewController {
    var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        refreshPreview()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    func refreshPreview() {
        previewLayer?.removeFromSuperlayer()
        guard let captureSession else { return }

        let layer = AVCaptureVideoPreviewLayer(session: captureSession)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer
    }
}
