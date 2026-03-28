import SwiftUI

@main
struct Virtual_Race_EngineerApp: App {
    @State private var webSocket = WebSocketService()
    @State private var speech = SpeechService()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(webSocket)
                .environment(speech)
        }
    }
}
