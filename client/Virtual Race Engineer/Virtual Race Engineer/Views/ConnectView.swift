import SwiftUI

struct ConnectView: View {
    @Environment(WebSocketService.self) private var webSocket
    @AppStorage("backendHost") private var host = "http://localhost:8080"
    @AppStorage("sessionUid") private var sessionUid = ""

    var onConnected: () -> Void

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)

            Text("Virtual Race Engineer")
                .font(.title)
                .fontWeight(.bold)

            VStack(spacing: 16) {
                TextField("Backend URL", text: $host)
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)

                TextField("Session UID", text: $sessionUid)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            .padding(.horizontal)

            Button {
                webSocket.connect(host: host, sessionUid: sessionUid)
                onConnected()
            } label: {
                Text("Connect")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal)
            .disabled(host.isEmpty || sessionUid.isEmpty)

            Spacer()
            Spacer()
        }
    }
}
