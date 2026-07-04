import Foundation
import Combine
import Network
import Darwin

@MainActor
final class CameraDiscoveryStore: ObservableObject {
    @Published var endpoints: [CameraEndpoint] = []
    @Published var statusText = "Ready"
    @Published var isRefreshing = false
    @Published var lastRefresh: Date?
    @Published var errorMessage: String?

    private let coordinator = CameraDiscoveryCoordinator()

    func refresh(includeSubnetScan: Bool) {
        guard !isRefreshing else { return }

        isRefreshing = true
        errorMessage = nil
        statusText = includeSubnetScan ? "Deep scanning the local subnet…" : "Looking for Bonjour and ONVIF devices…"

        Task { @MainActor in
            do {
                let discovered = try await coordinator.discover(includeSubnetScan: includeSubnetScan)
                endpoints = discovered.deduplicatedAndSorted()
                lastRefresh = .now
                statusText = endpoints.isEmpty ? "No cameras found yet." : "\(endpoints.count) camera candidates found."
            } catch {
                errorMessage = error.localizedDescription
                statusText = "Discovery failed."
            }
            isRefreshing = false
        }
    }
}

struct CameraDiscoveryCoordinator {
    private let bonjourDiscovery = BonjourDiscoveryService()
    private let onvifDiscovery = OnvifDiscoveryService()
    private let subnetProbe = LocalNetworkProbeService()

    func discover(includeSubnetScan: Bool) async throws -> [CameraEndpoint] {
        async let bonjourResults = bonjourDiscovery.discover()
        async let onvifResults = onvifDiscovery.discover()

        var results = await bonjourResults + onvifResults

        if includeSubnetScan {
            async let subnetResults = subnetProbe.discover()
            results += await subnetResults
        }

        return results
    }
}

struct BonjourDiscoveryService {
    func discover() async -> [CameraEndpoint] {
        async let rtspResults = BonjourServiceBrowser(serviceType: "_rtsp._tcp.").discover()
        async let onvifResults = BonjourServiceBrowser(serviceType: "_onvif._tcp.").discover()
        return await rtspResults + onvifResults
    }
}

final class BonjourServiceBrowser: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let serviceType: String
    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var endpoints: [CameraEndpoint] = []
    private var continuation: CheckedContinuation<[CameraEndpoint], Never>?
    private var finished = false

    init(serviceType: String) {
        self.serviceType = serviceType
    }

    @MainActor
    func discover(timeout: TimeInterval = 2.0) async -> [CameraEndpoint] {
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            browser.delegate = self
            browser.searchForServices(ofType: serviceType, inDomain: "local.")

            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                self?.finish()
            }
        }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        browser.stop()
        continuation?.resume(returning: endpoints.deduplicatedAndSorted())
        continuation = nil
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        services.append(service)
        service.delegate = self
        service.resolve(withTimeout: 1.5)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let hostName = sender.hostName, !hostName.isEmpty else { return }

        let port = sender.port
        let serviceName = sender.name.isEmpty ? hostName : sender.name
        let serviceTypeLower = serviceType.lowercased()
        let isOnvif = serviceTypeLower.contains("onvif")
        let path = isOnvif ? "/onvif/device_service" : ""

        var components = URLComponents()
        components.scheme = isOnvif ? "http" : "rtsp"
        components.host = hostName
        components.port = port >= 0 ? Int(port) : nil
        components.path = path

        guard let url = components.url else { return }

        let endpoint = CameraEndpoint(
            name: serviceName,
            source: .bonjour,
            transport: isOnvif ? .http : .rtsp,
            playbackStrategy: isOnvif ? .onvifDeviceService : .directStream,
            host: hostName,
            port: port >= 0 ? Int(port) : nil,
            path: path,
            streamURL: url,
            confidence: isOnvif ? 0.92 : 0.85,
            details: isOnvif ? "Bonjour ONVIF service" : "Bonjour RTSP service"
        )
        endpoints.append(endpoint)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String : NSNumber]) {
        finish()
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        finish()
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String : NSNumber]) {
        // Ignore unresolved entries; discovery continues.
    }
}

struct OnvifDiscoveryService {
    func discover() async -> [CameraEndpoint] {
        await OnvifWSDiscoveryProbe().discover()
    }
}

final class OnvifWSDiscoveryProbe {
    func discover(timeout: TimeInterval = 2.0) async -> [CameraEndpoint] {
        let probeBody = """
        <?xml version="1.0" encoding="UTF-8"?>
        <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                    xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
            <e:Header>
                <w:MessageID>uuid:\(UUID().uuidString)</w:MessageID>
                <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
            </e:Header>
            <e:Body>
                <d:Probe>
                    <d:Types>tds:Device</d:Types>
                </d:Probe>
            </e:Body>
        </e:Envelope>
        """

        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                var results: [CameraEndpoint] = []
                let socketDescriptor = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)

                guard socketDescriptor >= 0 else {
                    continuation.resume(returning: [])
                    return
                }

                defer { close(socketDescriptor) }

                var reuse: Int32 = 1
                setsockopt(socketDescriptor, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout.size(ofValue: reuse)))

                var multicastTTL: UInt8 = 1
                setsockopt(socketDescriptor, IPPROTO_IP, IP_MULTICAST_TTL, &multicastTTL, socklen_t(MemoryLayout.size(ofValue: multicastTTL)))

                var receiveTimeout = timeval(tv_sec: Int32(timeout), tv_usec: 0)
                setsockopt(socketDescriptor, SOL_SOCKET, SO_RCVTIMEO, &receiveTimeout, socklen_t(MemoryLayout<timeval>.size))

                var localAddress = sockaddr_in()
                localAddress.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                localAddress.sin_family = sa_family_t(AF_INET)
                localAddress.sin_port = 0
                localAddress.sin_addr = in_addr(s_addr: 0)

                var bindAddress = localAddress
                let bindResult = withUnsafePointer(to: &bindAddress) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        bind(socketDescriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }

                guard bindResult == 0 else {
                    continuation.resume(returning: [])
                    return
                }

                var multicastAddress = sockaddr_in()
                multicastAddress.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                multicastAddress.sin_family = sa_family_t(AF_INET)
                multicastAddress.sin_port = UInt16(3702).bigEndian
                multicastAddress.sin_addr = in_addr(s_addr: inet_addr("239.255.255.250"))

                guard let probeData = probeBody.data(using: .utf8) else {
                    continuation.resume(returning: [])
                    return
                }

                probeData.withUnsafeBytes { buffer in
                    withUnsafePointer(to: &multicastAddress) { pointer in
                        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                            sendto(socketDescriptor, buffer.baseAddress, buffer.count, 0, sockaddrPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }

                let deadline = Date().addingTimeInterval(timeout)
                while Date() < deadline {
                    var buffer = [UInt8](repeating: 0, count: 16_384)
                    var sourceAddress = sockaddr_storage()
                    var sourceLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
                    let received = buffer.withUnsafeMutableBytes { pointer -> Int in
                        recvfrom(
                            socketDescriptor,
                            pointer.baseAddress,
                            pointer.count,
                            0,
                            withUnsafeMutablePointer(to: &sourceAddress) {
                                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { $0 }
                            },
                            &sourceLength
                        )
                    }

                    guard received > 0 else {
                        break
                    }

                    let response = String(decoding: buffer.prefix(received), as: UTF8.self)
                    results.append(contentsOf: Self.parseProbeResponse(response))
                }

                continuation.resume(returning: results.deduplicatedAndSorted())
            }
        }
    }

    private static func parseProbeResponse(_ xml: String) -> [CameraEndpoint] {
        let xaddrs = regexCaptureGroups(pattern: #"<(?:\w+:)?XAddrs[^>]*>(.*?)</(?:\w+:)?XAddrs>"#, in: xml)
            .flatMap { $0.components(separatedBy: .whitespacesAndNewlines) }
            .filter { $0.hasPrefix("http://") || $0.hasPrefix("https://") }

        return xaddrs.compactMap { addressString in
            guard let url = URL(string: addressString), let host = url.host else { return nil }
            let path = url.path.isEmpty ? "/onvif/device_service" : url.path
            let endpoint = CameraEndpoint(
                name: host,
                source: .onvif,
                transport: .http,
                playbackStrategy: .onvifDeviceService,
                host: host,
                port: url.port,
                path: path,
                streamURL: url,
                confidence: 0.96,
                details: "WS-Discovery ONVIF service"
            )
            return endpoint
        }
    }
}

struct LocalNetworkProbeService {
    private let portsToProbe: [Int] = [80, 443, 554, 8554, 8000, 8080, 8899]
    private let scanTimeout: TimeInterval = 0.75

    func discover() async -> [CameraEndpoint] {
        guard let prefix = localIPv4Prefix() else { return [] }
        let localHost = localIPv4Address()

        return await withTaskGroup(of: [CameraEndpoint].self) { group in
            for octet in 1...254 {
                let host = "\(prefix).\(octet)"
                if host == localHost { continue }

                group.addTask {
                    await self.probeHost(host)
                }
            }

            var endpoints: [CameraEndpoint] = []
            for await result in group {
                endpoints += result
            }

            return endpoints.deduplicatedAndSorted()
        }
    }

    private func probeHost(_ host: String) async -> [CameraEndpoint] {
        var endpoints: [CameraEndpoint] = []

        for port in portsToProbe {
            guard await isTcpPortOpen(host: host, port: port, timeout: scanTimeout) else {
                continue
            }

            if port == 554 || port == 8554 {
                if let url = makeRTSPURL(host: host, port: port) {
                    endpoints.append(
                        CameraEndpoint(
                            name: "RTSP @ \(host)",
                            source: .lanProbe,
                            transport: .rtsp,
                            playbackStrategy: .directStream,
                            host: host,
                            port: port,
                            path: "/",
                            streamURL: url,
                            confidence: 0.65,
                            details: "Open RTSP port discovered during deep scan"
                        )
                    )
                }
                continue
            }

            if let webURL = makeHTTPURL(host: host, port: port), let fingerprint = await fingerprintHTTPService(url: webURL) {
                endpoints += fingerprint
            }
        }

        return endpoints
    }

    private func fingerprintHTTPService(url: URL) async -> [CameraEndpoint]? {
        do {
            let httpResponse = try await HTTPProbeClient().fetch(url: url)
            let body = httpResponse.body
            let lowercaseBody = body.lowercased()
            let isBaseusCandidate = lowercaseBody.contains("baseus") || lowercaseBody.contains("homestation") || lowercaseBody.contains("x1 pro") || lowercaseBody.contains("security")
            let streamURLs = extractStreamingURLs(from: body)

            var endpoints: [CameraEndpoint] = []

            if let firstStream = streamURLs.first {
                let transport: CameraTransport = firstStream.scheme?.lowercased() == "m3u8" ? .hls : (firstStream.scheme?.lowercased() == "rtsp" ? .rtsp : .http)
                let strategy: CameraPlaybackStrategy = firstStream.scheme?.lowercased() == "http" || firstStream.scheme?.lowercased() == "https" ? .directStream : .directStream

                endpoints.append(
                    CameraEndpoint(
                        name: httpResponse.title ?? url.host ?? "Stream endpoint",
                        source: isBaseusCandidate ? .baseusProbe : .lanProbe,
                        transport: transport,
                        playbackStrategy: strategy,
                        host: url.host ?? "",
                        port: url.port,
                        path: firstStream.path,
                        streamURL: firstStream,
                        confidence: isBaseusCandidate ? 0.75 : 0.7,
                        details: "Stream URL extracted from HTTP page"
                    )
                )
            }

            if isBaseusCandidate {
                endpoints.append(
                    CameraEndpoint(
                        name: httpResponse.title ?? "Baseus hub candidate",
                        source: .baseusProbe,
                        transport: .http,
                        playbackStrategy: .inspectionOnly,
                        host: url.host ?? "",
                        port: url.port,
                        path: url.path,
                        streamURL: url,
                        confidence: 0.5,
                        details: "Baseus / HomeStation fingerprint discovered"
                    )
                )
            } else if endpoints.isEmpty {
                endpoints.append(
                    CameraEndpoint(
                        name: httpResponse.title ?? (url.host ?? "HTTP service"),
                        source: .lanProbe,
                        transport: .http,
                        playbackStrategy: .inspectionOnly,
                        host: url.host ?? "",
                        port: url.port,
                        path: url.path,
                        streamURL: url,
                        confidence: 0.2,
                        details: "HTTP service responded during deep scan"
                    )
                )
            }

            return endpoints
        } catch {
            return nil
        }
    }

    private func isTcpPortOpen(host: String, port: Int, timeout: TimeInterval) async -> Bool {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return false }

        return await withCheckedContinuation { continuation in
            let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
            let queue = DispatchQueue(label: "camera.port.\(host).\(port)")
            var finished = false

            func finish(_ result: Bool) {
                guard !finished else { return }
                finished = true
                connection.cancel()
                continuation.resume(returning: result)
            }

            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    finish(true)
                case .failed, .cancelled:
                    finish(false)
                default:
                    break
                }
            }

            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) {
                finish(false)
            }
        }
    }

    private func makeHTTPURL(host: String, port: Int) -> URL? {
        var components = URLComponents()
        components.scheme = port == 443 ? "https" : "http"
        components.host = host
        components.port = port
        components.path = "/"
        return components.url
    }

    private func makeRTSPURL(host: String, port: Int) -> URL? {
        var components = URLComponents()
        components.scheme = "rtsp"
        components.host = host
        components.port = port
        components.path = "/"
        return components.url
    }
}

final class HTTPProbeClient: NSObject, URLSessionDelegate {
    struct Result {
        var body: String
        var title: String?
    }

    private let session: URLSession

    override init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1.25
        configuration.timeoutIntervalForResource = 1.25
        configuration.waitsForConnectivity = false
        let delegate = LocalNetworkSessionDelegate(credentials: nil)
        session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        super.init()
    }

    func fetch(url: URL) async throws -> Result {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 1.25
        request.setValue("OpenMonitor/1.0", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw CameraDiscoveryError.invalidResponse
        }

        let body = String(data: data.prefix(128_000), encoding: .utf8) ?? ""
        let title = Self.extractTitle(from: body)

        return Result(body: body, title: title ?? httpResponse.value(forHTTPHeaderField: "Server"))
    }

    private static func extractTitle(from body: String) -> String? {
        guard let match = regexCaptureGroups(pattern: #"<title[^>]*>(.*?)</title>"#, in: body).first else {
            return nil
        }
        return htmlUnescape(match.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

final class OnvifSOAPResolver {
    private let session: URLSession

    init(credentials: CameraCredentials?) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 3.0
        configuration.timeoutIntervalForResource = 3.0
        let delegate = LocalNetworkSessionDelegate(credentials: credentials)
        self.session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    func resolveStreamURL(deviceServiceURL: URL) async throws -> URL {
        let deviceResponse = try await postSOAP(
            to: deviceServiceURL,
            action: "GetCapabilities",
            body: """
            <tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                <tds:Category>All</tds:Category>
            </tds:GetCapabilities>
            """
        )

        guard let mediaXAddrString = regexCaptureGroups(pattern: #"<(?:\w+:)?Media[^>]*XAddr="([^"]+)"#, in: deviceResponse).first,
              let mediaURLString = htmlUnescape(mediaXAddrString),
              let mediaURL = URL(string: mediaURLString) else {
            throw CameraDiscoveryError.invalidResponse
        }

        let profilesResponse = try await postSOAP(
            to: mediaURL,
            action: "GetProfiles",
            body: """
            <trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl" />
            """
        )

        guard let profileToken = regexCaptureGroups(pattern: #"<(?:\w+:)?Profiles[^>]*token="([^"]+)"#, in: profilesResponse).first else {
            throw CameraDiscoveryError.invalidResponse
        }

        let streamResponse = try await postSOAP(
            to: mediaURL,
            action: "GetStreamUri",
            body: """
            <trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                <trt:StreamSetup>
                    <tt:Stream>RTP-Unicast</tt:Stream>
                    <tt:Transport>
                        <tt:Protocol>RTSP</tt:Protocol>
                    </tt:Transport>
                </trt:StreamSetup>
                <trt:ProfileToken>\(profileToken)</trt:ProfileToken>
            </trt:GetStreamUri>
            """
        )

        guard let streamURLString = regexCaptureGroups(pattern: #"<(?:\w+:)?Uri>(.*?)</(?:\w+:)?Uri>"#, in: streamResponse).first,
              let decoded = URL(string: htmlUnescape(streamURLString)) else {
            throw CameraDiscoveryError.invalidResponse
        }

        return decoded
    }

    private func postSOAP(to url: URL, action: String, body: String) async throws -> String {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 3.0
        request.setValue("application/soap+xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let soapAction: String
        switch action {
        case "GetCapabilities":
            soapAction = "http://www.onvif.org/ver10/device/wsdl/GetCapabilities"
        case "GetProfiles", "GetStreamUri":
            soapAction = "http://www.onvif.org/ver10/media/wsdl/\(action)"
        default:
            soapAction = action
        }
        request.setValue("\"\(soapAction)\"", forHTTPHeaderField: "SOAPAction")
        request.httpBody = soapEnvelope(body: body).data(using: .utf8)

        let (data, _) = try await session.data(for: request)
        return String(decoding: data, as: UTF8.self)
    }

    private func soapEnvelope(body: String) -> String {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope">
            <e:Header />
            <e:Body>
                \(body)
            </e:Body>
        </e:Envelope>
        """
    }
}

final class LocalNetworkSessionDelegate: NSObject, URLSessionDelegate {
    private let credentials: CameraCredentials?

    init(credentials: CameraCredentials?) {
        self.credentials = credentials
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
            return
        }

        guard let credentials else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        let credential = URLCredential(user: credentials.username, password: credentials.password, persistence: .forSession)
        completionHandler(.useCredential, credential)
    }
}

struct CameraCredentials: Hashable {
    var username: String
    var password: String
}

// MARK: - Utilities

private func localIPv4Address() -> String? {
    var addressPointer: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&addressPointer) == 0, let first = addressPointer else {
        return nil
    }
    defer { freeifaddrs(addressPointer) }

    for cursor in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let interface = cursor.pointee
        guard let address = interface.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else {
            continue
        }

        let name = String(cString: interface.ifa_name)
        if name == "lo0" {
            continue
        }

        var addr = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        inet_ntop(AF_INET, &addr, &buffer, socklen_t(INET_ADDRSTRLEN))
        return String(cString: buffer)
    }

    return nil
}

private func localIPv4Prefix() -> String? {
    guard let address = localIPv4Address() else { return nil }
    let components = address.split(separator: ".")
    guard components.count == 4 else { return nil }
    return components.prefix(3).joined(separator: ".")
}

private func extractStreamingURLs(from body: String) -> [URL] {
    let patterns = [
        #"rtsp://[^\s"'<>]+"#,
        #"https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"#,
        #"https?://[^\s"'<>]+\.mp4[^\s"'<>]*"#
    ]

    var urls: [URL] = []
    for pattern in patterns {
        for match in regexMatches(pattern: pattern, in: body) {
            let cleaned = trimTrailingPunctuation(match)
            if let url = URL(string: cleaned) {
                urls.append(url)
            }
        }
    }

    return urls.deduplicatedByAbsoluteString()
}

private func regexMatches(pattern: String, in text: String) -> [String] {
    guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
        return []
    }
    let range = NSRange(text.startIndex..<text.endIndex, in: text)
    return regex.matches(in: text, options: [], range: range).compactMap { match in
        guard match.numberOfRanges > 0, let range = Range(match.range, in: text) else { return nil }
        return String(text[range])
    }
}

private func regexCaptureGroups(pattern: String, in text: String) -> [String] {
    guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
        return []
    }
    let range = NSRange(text.startIndex..<text.endIndex, in: text)
    return regex.matches(in: text, options: [], range: range).compactMap { match in
        guard match.numberOfRanges > 1, let range = Range(match.range(at: 1), in: text) else { return nil }
        return String(text[range])
    }
}

private func trimTrailingPunctuation(_ value: String) -> String {
    value.trimmingCharacters(in: CharacterSet(charactersIn: "\"'<>),.;"))
}

private func htmlUnescape(_ text: String) -> String {
    text
        .replacingOccurrences(of: "&amp;", with: "&")
        .replacingOccurrences(of: "&lt;", with: "<")
        .replacingOccurrences(of: "&gt;", with: ">")
        .replacingOccurrences(of: "&quot;", with: "\"")
        .replacingOccurrences(of: "&apos;", with: "'")
}

private extension Array where Element == URL {
    func deduplicatedByAbsoluteString() -> [URL] {
        var seen = Set<String>()
        var values: [URL] = []
        for url in self {
            let key = url.absoluteString
            if seen.insert(key).inserted {
                values.append(url)
            }
        }
        return values
    }
}
