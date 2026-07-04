import Foundation
import XCTest
@testable import OpenMonitor

final class OpenMonitorTests: XCTestCase {
    func testManualRTSPBuildsValidStreamURL() throws {
        let endpoint = try CameraEndpoint.manualRTSP(
            host: "192.168.1.20",
            port: 554,
            path: "/live"
        )

        XCTAssertEqual(endpoint.streamURL.absoluteString, "rtsp://192.168.1.20:554/live")
        XCTAssertEqual(endpoint.playbackStrategy, .directStream)
        XCTAssertEqual(endpoint.transport, .rtsp)
    }

    func testManualONVIFBuildsDeviceServiceURL() throws {
        let endpoint = try CameraEndpoint.manualONVIF(
            host: "192.168.1.21",
            port: 80
        )

        XCTAssertEqual(endpoint.streamURL.absoluteString, "http://192.168.1.21:80/onvif/device_service")
        XCTAssertEqual(endpoint.playbackStrategy, .onvifDeviceService)
        XCTAssertEqual(endpoint.transport, .http)
    }

    func testDeduplicationPrefersHigherConfidenceEndpoint() {
        let lowerConfidence = CameraEndpoint(
            name: "Camera A",
            source: .lanProbe,
            transport: .rtsp,
            playbackStrategy: .directStream,
            host: "192.168.1.22",
            port: 554,
            path: "/",
            streamURL: URL(string: "rtsp://192.168.1.22:554/")!,
            confidence: 0.1,
            details: "Low confidence"
        )

        let higherConfidence = CameraEndpoint(
            name: "Camera A",
            source: .lanProbe,
            transport: .rtsp,
            playbackStrategy: .directStream,
            host: "192.168.1.22",
            port: 554,
            path: "/",
            streamURL: URL(string: "rtsp://192.168.1.22:554/")!,
            confidence: 0.9,
            details: "Higher confidence"
        )

        let deduped = [lowerConfidence, higherConfidence].deduplicatedAndSorted()

        XCTAssertEqual(deduped.count, 1)
        XCTAssertEqual(deduped.first?.confidence, 0.9)
        XCTAssertTrue(deduped.first?.details.contains("Higher confidence") == true)
    }
}
