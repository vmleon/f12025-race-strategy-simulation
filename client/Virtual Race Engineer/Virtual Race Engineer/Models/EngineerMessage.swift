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

    var id: Int64 { timestamp }

    var date: Date {
        Date(timeIntervalSince1970: Double(timestamp) / 1000.0)
    }
}
