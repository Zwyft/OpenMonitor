import SwiftUI

struct CameraBrowserView: View {
    @StateObject private var discoveryStore = CameraDiscoveryStore()
    @State private var navigationPath = NavigationPath()
    @State private var manualMode: ManualCameraMode = .rtsp
    @State private var manualHost = ""
    @State private var manualPort = "554"
    @State private var manualPath = "/stream"
    @State private var manualUsername = ""
    @State private var manualPassword = ""

    var body: some View {
        NavigationStack(path: $navigationPath) {
            List {
                discoveryStatusSection
                discoveredEndpointsSection
                manualEntrySection
            }
            .navigationTitle("Network Cameras")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Rescan") {
                        discoveryStore.refresh(includeSubnetScan: false)
                    }

                    Button("Deep Scan") {
                        discoveryStore.refresh(includeSubnetScan: true)
                    }
                }
            }
            .navigationDestination(for: CameraEndpoint.self) { endpoint in
                CameraPlaybackView(endpoint: endpoint)
            }
            .task {
                if discoveryStore.endpoints.isEmpty {
                    discoveryStore.refresh(includeSubnetScan: false)
                }
            }
            .alert("Discovery Error", isPresented: discoveryErrorBinding) {
                Button("OK") {
                    discoveryStore.errorMessage = nil
                }
            } message: {
                Text(discoveryStore.errorMessage ?? "")
            }
        }
    }

    private var discoveryErrorBinding: Binding<Bool> {
        Binding(
            get: { discoveryStore.errorMessage != nil },
            set: { if !$0 { discoveryStore.errorMessage = nil } }
        )
    }

    private var discoveryStatusSection: some View {
        Section {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(discoveryStore.statusText)
                        .font(.headline)
                    if let lastRefresh = discoveryStore.lastRefresh {
                        Text("Last refreshed \(lastRefresh.formatted(date: .omitted, time: .shortened))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                if discoveryStore.isRefreshing {
                    ProgressView()
                }
            }
        }
    }

    private var discoveredEndpointsSection: some View {
        Section("Discovered") {
            if discoveryStore.endpoints.isEmpty {
                ContentUnavailableView(
                    "No camera streams found",
                    systemImage: "dot.radiowaves.left.and.right",
                    description: Text("Use Deep Scan to inspect your subnet or add a manual RTSP/ONVIF endpoint.")
                )
                .listRowBackground(Color.clear)
            } else {
                ForEach(discoveryStore.endpoints) { endpoint in
                    NavigationLink(value: endpoint) {
                        CameraEndpointRow(endpoint: endpoint)
                    }
                }
            }
        }
    }

    private var manualEntrySection: some View {
        Section("Manual Endpoint") {
            Picker("Mode", selection: $manualMode) {
                ForEach(ManualCameraMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(.segmented)

            TextField("Host or IP", text: $manualHost)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            TextField("Port", text: $manualPort)
                .keyboardType(.numberPad)

            TextField(manualMode == .rtsp ? "RTSP Path" : "ONVIF Path", text: $manualPath)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            TextField("Username (optional)", text: $manualUsername)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            SecureField("Password (optional)", text: $manualPassword)

            Button("Add & Open") {
                openManualEndpoint()
            }
            .disabled(manualHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func openManualEndpoint() {
        let host = manualHost.trimmingCharacters(in: .whitespacesAndNewlines)
        let port = Int(manualPort.trimmingCharacters(in: .whitespacesAndNewlines)) ?? (manualMode == .rtsp ? 554 : 80)

        do {
            let endpoint: CameraEndpoint
            if manualMode == .rtsp {
                endpoint = try CameraEndpoint.manualRTSP(
                    host: host,
                    port: port,
                    path: manualPath,
                    username: manualUsername.isEmpty ? nil : manualUsername,
                    password: manualPassword.isEmpty ? nil : manualPassword
                )
            } else {
                endpoint = try CameraEndpoint.manualONVIF(
                    host: host,
                    port: port,
                    path: manualPath,
                    username: manualUsername.isEmpty ? nil : manualUsername,
                    password: manualPassword.isEmpty ? nil : manualPassword
                )
            }

            navigationPath.append(endpoint)
        } catch {
            discoveryStore.errorMessage = error.localizedDescription
        }
    }
}

private enum ManualCameraMode: String, CaseIterable, Identifiable {
    case rtsp = "RTSP"
    case onvif = "ONVIF"

    var id: String { rawValue }
}

private struct CameraEndpointRow: View {
    let endpoint: CameraEndpoint

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(endpoint.name)
                    .font(.headline)
                Spacer()
                Text(endpoint.source.rawValue)
                    .font(.caption.bold())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.thinMaterial, in: Capsule())
            }

            Text(endpoint.subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            HStack(spacing: 8) {
                Label(endpoint.transport.rawValue, systemImage: "dot.radiowaves.left.and.right")
                Text(endpoint.displayAddress)
            }
            .font(.caption2)
            .foregroundStyle(.secondary)

            if !endpoint.details.isEmpty {
                Text(endpoint.details)
                    .font(.caption2)
                    .foregroundStyle(endpoint.isBaseusCandidate ? .orange : .secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
