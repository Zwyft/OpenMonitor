import SwiftUI
import UIKit
import VLCKitSPM

struct CameraPlaybackView: View {
    let endpoint: CameraEndpoint
    @StateObject private var viewModel: CameraPlaybackViewModel

    init(endpoint: CameraEndpoint) {
        self.endpoint = endpoint
        _viewModel = StateObject(wrappedValue: CameraPlaybackViewModel(endpoint: endpoint))
    }

    var body: some View {
        VStack(spacing: 12) {
            if let resolvedURL = viewModel.resolvedURL {
                VLCPlayerView(url: resolvedURL)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(alignment: .topLeading) {
                        Text(endpoint.source.rawValue)
                            .font(.caption.weight(.semibold))
                            .padding(8)
                            .background(.black.opacity(0.45), in: Capsule())
                            .foregroundStyle(.white)
                            .padding()
                    }
            } else {
                ContentUnavailableView(
                    "Preparing stream",
                    systemImage: "video",
                    description: Text(viewModel.statusText)
                )
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(endpoint.name)
                    .font(.headline)
                Text(endpoint.streamURL.absoluteString)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)

                if let errorMessage = viewModel.errorMessage {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack {
                Button("Reconnect") {
                    viewModel.restart()
                }
                .buttonStyle(.bordered)

                Spacer()
            }
        }
        .padding()
        .navigationTitle(endpoint.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            viewModel.start()
        }
        .onDisappear {
            viewModel.stop()
        }
    }
}

@MainActor
final class CameraPlaybackViewModel: ObservableObject {
    @Published var resolvedURL: URL?
    @Published var statusText = "Connecting to stream…"
    @Published var errorMessage: String?

    private let endpoint: CameraEndpoint
    private var task: Task<Void, Never>?

    init(endpoint: CameraEndpoint) {
        self.endpoint = endpoint
    }

    func start() {
        guard task == nil else { return }

        task = Task { @MainActor in
            await self.connect()
            self.task = nil
        }
    }

    func restart() {
        stop()
        start()
    }

    func stop() {
        task?.cancel()
        task = nil
        resolvedURL = nil
    }

    private func connect() async {
        statusText = "Connecting to \(endpoint.name)…"
        errorMessage = nil

        do {
            let streamURL = try await resolvedPlaybackURL(for: endpoint)
            guard !Task.isCancelled else { return }

            resolvedURL = streamURL
            statusText = "Streaming \(streamURL.absoluteString)"
        } catch {
            guard !Task.isCancelled else { return }

            if endpoint.playbackStrategy == .directStream {
                resolvedURL = endpoint.streamURL
                statusText = "Streaming direct URL"
                errorMessage = nil
            } else {
                errorMessage = error.localizedDescription
                statusText = "Unable to resolve a playable stream."
            }
        }
    }

    private func resolvedPlaybackURL(for endpoint: CameraEndpoint) async throws -> URL {
        let credentials: CameraCredentials? = {
            if let username = endpoint.username, let password = endpoint.password {
                return CameraCredentials(username: username, password: password)
            }
            return nil
        }()

        switch endpoint.playbackStrategy {
        case .directStream:
            return authenticatedURL(endpoint.streamURL, credentials: credentials)
        case .inspectionOnly:
            if endpoint.streamURL.scheme?.lowercased() == "rtsp" || endpoint.streamURL.path.lowercased().contains(".m3u8") {
                return authenticatedURL(endpoint.streamURL, credentials: credentials)
            }
            throw CameraDiscoveryError.invalidResponse
        case .onvifDeviceService:
            let resolvedURL = try await OnvifSOAPResolver(credentials: credentials).resolveStreamURL(
                deviceServiceURL: endpoint.streamURL
            )
            return authenticatedURL(resolvedURL, credentials: credentials)
        }
    }

    private func authenticatedURL(_ url: URL, credentials: CameraCredentials?) -> URL {
        guard let credentials else { return url }

        var components = URLComponents(url: url, resolvingAgainstBaseURL: false) ?? URLComponents()
        components.scheme = url.scheme
        components.host = url.host
        components.port = url.port
        components.path = url.path
        components.query = url.query
        components.fragment = url.fragment
        components.user = credentials.username
        components.password = credentials.password
        return components.url ?? url
    }
}

struct VLCPlayerView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        context.coordinator.attach(to: view)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.play(url: url)
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.stop()
    }

    final class Coordinator: NSObject {
        private let player = VLCMediaPlayer()
        private var currentURL: URL?

        func attach(to view: UIView) {
            player.drawable = view
        }

        func play(url: URL) {
            guard currentURL != url else { return }
            currentURL = url
            player.stop()
            let media = VLCMedia(url: url)
            player.media = media
            player.play()
        }

        func stop() {
            currentURL = nil
            player.stop()
        }
    }
}
