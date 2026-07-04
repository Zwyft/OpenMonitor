import Foundation

enum CameraSource: String, Codable, CaseIterable, Identifiable {
    case onvif = "ONVIF"
    case bonjour = "Bonjour"
    case lanProbe = "LAN Probe"
    case baseusProbe = "Baseus Probe"
    case manual = "Manual"
    case localCapture = "Local Capture"

    var id: String { rawValue }
}

enum CameraTransport: String, Codable {
    case rtsp = "RTSP"
    case http = "HTTP"
    case hls = "HLS"
    case localCapture = "Local Capture"
}

enum CameraPlaybackStrategy: String, Codable {
    case directStream
    case onvifDeviceService
    case inspectionOnly
}

struct CameraEndpoint: Identifiable, Hashable {
    let id: UUID
    var name: String
    var source: CameraSource
    var transport: CameraTransport
    var playbackStrategy: CameraPlaybackStrategy
    var host: String
    var port: Int?
    var path: String
    var streamURL: URL
    var username: String?
    var password: String?
    var confidence: Double
    var details: String
    var lastSeen: Date
    var isBaseusCandidate: Bool

    init(
        id: UUID = UUID(),
        name: String,
        source: CameraSource,
        transport: CameraTransport,
        playbackStrategy: CameraPlaybackStrategy,
        host: String,
        port: Int? = nil,
        path: String = "",
        streamURL: URL,
        username: String? = nil,
        password: String? = nil,
        confidence: Double = 0.5,
        details: String = "",
        lastSeen: Date = .now,
        isBaseusCandidate: Bool = false
    ) {
        self.id = id
        self.name = name
        self.source = source
        self.transport = transport
        self.playbackStrategy = playbackStrategy
        self.host = host
        self.port = port
        self.path = path
        self.streamURL = streamURL
        self.username = username
        self.password = password
        self.confidence = confidence
        self.details = details
        self.lastSeen = lastSeen
        self.isBaseusCandidate = isBaseusCandidate
    }

    var dedupeKey: String {
        "\(transport.rawValue)|\(host.lowercased())|\(port ?? 0)|\(path.lowercased())"
    }

    var displayAddress: String {
        if let port {
            return "\(host):\(port)"
        }
        return host
    }

    var subtitle: String {
        let portText = port.map { ":\($0)" } ?? ""
        var pieces = ["\(transport.rawValue) \(host)\(portText)"]
        if !details.isEmpty {
            pieces.append(details)
        }
        return pieces.joined(separator: " • ")
    }

    var shortDetail: String {
        if !details.isEmpty {
            return details
        }
        return "\(source.rawValue) • \(transport.rawValue)"
    }

    static func manualRTSP(
        host: String,
        port: Int,
        path: String,
        username: String? = nil,
        password: String? = nil
    ) throws -> CameraEndpoint {
        var components = URLComponents()
        components.scheme = "rtsp"
        components.host = host
        components.port = port
        components.path = path.hasPrefix("/") ? path : "/" + path
        components.user = username
        components.password = password

        guard let url = components.url else {
            throw CameraDiscoveryError.invalidManualURL
        }

        return CameraEndpoint(
            name: host,
            source: .manual,
            transport: .rtsp,
            playbackStrategy: .directStream,
            host: host,
            port: port,
            path: components.path,
            streamURL: url,
            username: username,
            password: password,
            confidence: 1.0,
            details: "Manual RTSP stream"
        )
    }

    static func manualONVIF(
        host: String,
        port: Int,
        path: String = "/onvif/device_service",
        username: String? = nil,
        password: String? = nil
    ) throws -> CameraEndpoint {
        var components = URLComponents()
        components.scheme = "http"
        components.host = host
        components.port = port
        components.path = path.hasPrefix("/") ? path : "/" + path
        components.user = username
        components.password = password

        guard let url = components.url else {
            throw CameraDiscoveryError.invalidManualURL
        }

        return CameraEndpoint(
            name: host,
            source: .manual,
            transport: .http,
            playbackStrategy: .onvifDeviceService,
            host: host,
            port: port,
            path: components.path,
            streamURL: url,
            username: username,
            password: password,
            confidence: 1.0,
            details: "Manual ONVIF device service"
        )
    }
}

enum CameraDiscoveryError: LocalizedError {
    case invalidManualURL
    case invalidResponse
    case noLocalAddress
    case probeFailed

    var errorDescription: String? {
        switch self {
        case .invalidManualURL:
            return "The manual camera URL could not be built."
        case .invalidResponse:
            return "The camera returned data that could not be parsed."
        case .noLocalAddress:
            return "No local Wi‑Fi address was available for subnet scanning."
        case .probeFailed:
            return "The network probe failed."
        }
    }
}

extension Array where Element == CameraEndpoint {
    func deduplicatedAndSorted() -> [CameraEndpoint] {
        var byKey: [String: CameraEndpoint] = [:]

        for endpoint in self {
            let key = endpoint.dedupeKey
            if let existing = byKey[key] {
                if endpoint.confidence > existing.confidence || endpoint.lastSeen > existing.lastSeen {
                    byKey[key] = merge(existing: existing, with: endpoint)
                }
            } else {
                byKey[key] = endpoint
            }
        }

        return byKey.values.sorted {
            if $0.confidence != $1.confidence {
                return $0.confidence > $1.confidence
            }
            if $0.source != $1.source {
                return $0.source.rawValue < $1.source.rawValue
            }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private func merge(existing: CameraEndpoint, with replacement: CameraEndpoint) -> CameraEndpoint {
        var merged = existing
        if replacement.confidence >= existing.confidence {
            merged.source = replacement.source
        }
        merged.name = replacement.name.isEmpty ? existing.name : replacement.name
        merged.transport = replacement.transport
        merged.playbackStrategy = replacement.playbackStrategy
        merged.streamURL = replacement.streamURL
        merged.username = replacement.username ?? existing.username
        merged.password = replacement.password ?? existing.password
        merged.confidence = max(existing.confidence, replacement.confidence)
        merged.details = [existing.details, replacement.details].filter { !$0.isEmpty }.joined(separator: " • ")
        merged.lastSeen = max(existing.lastSeen, replacement.lastSeen)
        merged.isBaseusCandidate = existing.isBaseusCandidate || replacement.isBaseusCandidate
        return merged
    }
}
