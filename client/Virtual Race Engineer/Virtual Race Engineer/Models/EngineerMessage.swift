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

    private static var nextId: Int64 = 0
    let id: Int64

    var date: Date {
        Date(timeIntervalSince1970: Double(timestamp) / 1000.0)
    }

    enum CodingKeys: String, CodingKey {
        case type, sessionUid, priority, text, timestamp
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(String.self, forKey: .type)
        sessionUid = try container.decode(String.self, forKey: .sessionUid)
        priority = try container.decode(MessagePriority.self, forKey: .priority)
        text = try container.decode(String.self, forKey: .text)
        timestamp = try container.decode(Int64.self, forKey: .timestamp)
        Self.nextId += 1
        id = Self.nextId
    }
}
