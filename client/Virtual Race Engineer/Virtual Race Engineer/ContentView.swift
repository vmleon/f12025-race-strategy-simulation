import SwiftUI

struct ContentView: View {
    @State private var isConnected = false
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            Group {
                if isConnected {
                    LiveView(onDisconnect: { isConnected = false })
                } else {
                    ConnectView(onConnected: { isConnected = true })
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
            }
        }
    }
}
