import SwiftUI

struct LiveView: View {
    @Environment(WebSocketService.self) private var webSocket
    @Environment(SpeechService.self) private var speech

    var onDisconnect: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            statusBar
            Divider()
            messageLog
            Divider()
            bottomBar
        }
        .onAppear {
            webSocket.onMessage = { message in
                speech.speak(message.text, priority: message.priority)
            }
        }
        .onDisappear {
            webSocket.onMessage = nil
        }
    }

    private var statusBar: some View {
        HStack {
            Circle()
                .fill(statusColor)
                .frame(width: 10, height: 10)
            Text(webSocket.state.label)
                .font(.subheadline)
            Spacer()
            Text("\(webSocket.messages.count) messages")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .background(.bar)
    }

    private var statusColor: Color {
        switch webSocket.state {
        case .connected: .green
        case .connecting, .reconnecting: .orange
        case .disconnected: .red
        }
    }

    private var messageLog: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(webSocket.messages) { message in
                        MessageRow(message: message)
                            .id(message.id)
                    }
                }
                .padding()
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: webSocket.messages.count) {
                guard let last = webSocket.messages.last else { return }
                withAnimation {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }

    private var bottomBar: some View {
        HStack {
            Button("Disconnect", role: .destructive) {
                speech.stop()
                webSocket.disconnect()
                onDisconnect()
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .background(.bar)
    }
}

private struct MessageRow: View {
    let message: EngineerMessage

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                priorityBadge
                Spacer()
                Text(message.date, style: .time)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Text(message.displayText)
                .font(.body)
                .textSelection(.enabled)  // cherry-pick individual sentences/words
        }
        .padding(12)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .contextMenu {
            Button {
                UIPasteboard.general.string = message.displayText
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }
        }
    }

    private var priorityBadge: some View {
        Text(message.priority.rawValue)
            .font(.caption2)
            .fontWeight(.semibold)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(badgeColor.opacity(0.2))
            .foregroundStyle(badgeColor)
            .clipShape(Capsule())
    }

    private var badgeColor: Color {
        switch message.priority {
        case .IMMEDIATE: .red
        case .HIGH: .orange
        case .NORMAL: .blue
        }
    }
}
