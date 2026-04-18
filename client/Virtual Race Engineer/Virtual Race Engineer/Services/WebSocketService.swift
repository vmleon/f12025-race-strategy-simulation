import Foundation
import Observation
import OSLog

private let log = Logger(subsystem: "dev.victormartin.VirtualRaceEngineer", category: "websocket")

@Observable
final class WebSocketService: @unchecked Sendable {
    private(set) var state: ConnectionState = .disconnected
    private(set) var messages: [EngineerMessage] = []

    private var webSocketTask: URLSessionWebSocketTask?
    private var session: URLSession?
    private var host: String = ""
    private var sessionUid: String = ""
    private var reconnectAttempt = 0
    private let maxReconnectAttempt = 10
    private var reconnectTask: Task<Void, Never>?

    var onMessage: ((EngineerMessage) -> Void)?

    func connect(host: String, sessionUid: String) {
        log.info("connect host=\(host, privacy: .public) sessionUid=\(sessionUid, privacy: .public)")
        self.host = host
        self.sessionUid = sessionUid
        self.reconnectAttempt = 0
        messages = []
        openConnection()
    }

    func disconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        session?.invalidateAndCancel()
        session = nil
        state = .disconnected
    }

    private func openConnection() {
        let scheme = host.hasPrefix("https") ? "wss" : "ws"
        let cleanHost = host
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        guard let url = URL(string: "\(scheme)://\(cleanHost)/ws/race-engineer") else {
            log.error("openConnection: invalid URL host=\(self.host, privacy: .public)")
            state = .disconnected
            return
        }

        log.info("openConnection url=\(url.absoluteString, privacy: .public) attempt=\(self.reconnectAttempt)")
        state = reconnectAttempt == 0 ? .connecting : .reconnecting(attempt: reconnectAttempt)

        session = URLSession(configuration: .default)
        webSocketTask = session?.webSocketTask(with: url)
        webSocketTask?.resume()

        state = .connected
        receiveMessage()
    }

    private func receiveMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self else { return }

            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleText(text)
                case .data(let data):
                    self.handleData(data)
                @unknown default:
                    break
                }
                self.receiveMessage()

            case .failure(let error):
                log.error("receive failure: \(error.localizedDescription, privacy: .public)")
                self.scheduleReconnect()
            }
        }
    }

    private func handleText(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        handleData(data)
    }

    private struct SessionEvent: Decodable {
        let type: String
        let sessionUid: String
    }

    private func handleData(_ data: Data) {
        if let event = try? JSONDecoder().decode(SessionEvent.self, from: data),
           event.type == "sessionStarted" {
            if event.sessionUid != sessionUid {
                log.info("sessionStarted: switching sessionUid \(self.sessionUid, privacy: .public) -> \(event.sessionUid, privacy: .public)")
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    MainActor.assumeIsolated {
                        self.sessionUid = event.sessionUid
                        self.messages = []
                    }
                }
            } else {
                log.debug("sessionStarted: same uid \(event.sessionUid, privacy: .public), ignoring")
            }
            return
        }

        guard let message = try? JSONDecoder().decode(EngineerMessage.self, from: data) else {
            // Not an EngineerMessage shape — likely a state/event broadcast; ignore quietly.
            return
        }
        guard message.sessionUid == sessionUid else {
            log.warning("DROP message with sessionUid=\(message.sessionUid, privacy: .public) (expected \(self.sessionUid, privacy: .public)) text=\"\(message.text, privacy: .public)\"")
            return
        }

        log.info("ACCEPT priority=\(message.priority.rawValue, privacy: .public) text=\"\(message.text, privacy: .public)\"")
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            MainActor.assumeIsolated {
                self.messages.append(message)
                self.onMessage?(message)
            }
        }
    }

    private func scheduleReconnect() {
        guard reconnectAttempt < maxReconnectAttempt else {
            Task { @MainActor in self.state = .disconnected }
            return
        }

        reconnectAttempt += 1
        let delay = min(pow(2.0, Double(reconnectAttempt)), 30.0)

        Task { @MainActor in
            self.state = .reconnecting(attempt: self.reconnectAttempt)
        }

        reconnectTask = Task {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            Task { @MainActor in self.openConnection() }
        }
    }
}
