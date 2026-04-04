import Foundation

enum MessagePriority: String, Codable {
    case IMMEDIATE
    case HIGH
    case NORMAL
}

struct EngineerMessage: Codable, Identifiable {
    let type: String
    let sessionUid: String
    let priority: MessagePriority
    let text: String
    let timestamp: Int64

    let id = UUID()

    var date: Date {
        Date(timeIntervalSince1970: Double(timestamp) / 1000.0)
    }

    enum CodingKeys: String, CodingKey {
        case type, sessionUid, priority, text, timestamp
    }
}
