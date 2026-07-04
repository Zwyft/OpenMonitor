import SwiftUI

struct SelectInputView: View {
    var body: some View {
        TabView {
            CameraBrowserView()
                .tabItem {
                    Label("Network", systemImage: "wifi.router")
                }

            MonitorView()
                .tabItem {
                    Label("Local", systemImage: "camera")
                }
        }
    }
}

