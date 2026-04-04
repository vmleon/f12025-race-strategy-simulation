import SwiftUI

struct ConnectView: View {
    @Environment(WebSocketService.self) private var webSocket
    @AppStorage("backendHost") private var host = "http://localhost:8080"

    var onConnected: () -> Void

    @State private var sessions: [ActiveSession] = []
    @State private var selectedUid: String?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var autoConnecting = true

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 200, height: 200)

            if autoConnecting {
                ProgressView()
                    .controlSize(.large)
                Text("Looking for sessions...")
                    .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 16) {
                    TextField("Backend URL", text: $host)
                        .textFieldStyle(.roundedBorder)
                        .textContentType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    Button {
                        fetchSessions()
                    } label: {
                        HStack {
                            if isLoading {
                                ProgressView()
                                    .controlSize(.small)
                            }
                            Text("Fetch Sessions")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(host.isEmpty || isLoading)

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundColor(.red)
                    }

                    if !sessions.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Active Sessions")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            ForEach(sessions) { session in
                                Button {
                                    selectedUid = session.sessionUid
                                } label: {
                                    HStack {
                                        VStack(alignment: .leading) {
                                            Text(session.trackName)
                                                .font(.body)
                                            if !session.sessionType.isEmpty {
                                                Text(session.sessionType)
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                        Spacer()
                                        if selectedUid == session.sessionUid {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundColor(.accentColor)
                                        }
                                    }
                                    .padding(.vertical, 8)
                                    .padding(.horizontal, 12)
                                    .background(
                                        RoundedRectangle(cornerRadius: 8)
                                            .fill(selectedUid == session.sessionUid
                                                  ? Color.accentColor.opacity(0.1)
                                                  : Color.clear)
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    } else if !isLoading && errorMessage == nil && sessions.isEmpty {
                        // only show after a fetch has been attempted
                    }
                }
                .padding(.horizontal)

                Button {
                    if let uid = selectedUid {
                        webSocket.connect(host: host, sessionUid: uid)
                        onConnected()
                    }
                } label: {
                    Text("Connect")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal)
                .disabled(selectedUid == nil)
            }

            Spacer()
            Spacer()
        }
        .task {
            await autoConnect()
        }
    }

    private func fetchSessions() {
        isLoading = true
        errorMessage = nil
        sessions = []
        selectedUid = nil

        let cleanHost = host.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(cleanHost)/api/sessions/active") else {
            errorMessage = "Invalid URL"
            isLoading = false
            return
        }

        URLSession.shared.dataTask(with: url) { data, response, error in
            Task { @MainActor in
                isLoading = false

                if let error {
                    errorMessage = error.localizedDescription
                    return
                }

                guard let data else {
                    errorMessage = "No data received"
                    return
                }

                do {
                    let decoded = try JSONDecoder().decode([ActiveSession].self, from: data)
                    sessions = decoded
                    if decoded.count == 1 {
                        selectedUid = decoded.first?.sessionUid
                    }
                    if decoded.isEmpty {
                        errorMessage = "No active sessions"
                    }
                } catch {
                    errorMessage = "Failed to parse response"
                }
            }
        }.resume()
    }

    private func autoConnect() async {
        guard !host.isEmpty else {
            autoConnecting = false
            return
        }

        let first = await fetchSessionsAsync()

        try? await Task.sleep(for: .seconds(2.5))
        guard !Task.isCancelled else { await MainActor.run { autoConnecting = false }; return }

        let second = await fetchSessionsAsync()

        if let firstSession = first, first?.count == 1,
           let secondSession = second, second?.count == 1,
           firstSession[0].sessionUid == secondSession[0].sessionUid {
            let uid = firstSession[0].sessionUid
            await MainActor.run {
                webSocket.connect(host: host, sessionUid: uid)
                onConnected()
            }
        } else {
            await MainActor.run {
                if let second, !second.isEmpty {
                    sessions = second
                    if second.count == 1 {
                        selectedUid = second[0].sessionUid
                    }
                }
                autoConnecting = false
            }
        }
    }

    private func fetchSessionsAsync() async -> [ActiveSession]? {
        let cleanHost = host.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(cleanHost)/api/sessions/active") else {
            return nil
        }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            return try JSONDecoder().decode([ActiveSession].self, from: data)
        } catch {
            return nil
        }
    }
}
