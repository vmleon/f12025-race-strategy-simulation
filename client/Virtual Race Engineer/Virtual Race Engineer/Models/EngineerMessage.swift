import Foundation

enum MessagePriority: String, Codable {
    case IMMEDIATE
    case HIGH
    case NORMAL
}

struct EngineerMessage: Codable, Identifiable {
    /// The backend marks sentence boundaries with this character (it inserts it only at
    /// real boundaries, never inside decimals). The client splits on it for per-sentence
    /// TTS and swaps it back to a space for display/copy.
    static let sentenceSeparator = "|"

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

    /// Human-readable text with the sentence separator swapped back to a space.
    var displayText: String {
        text.replacingOccurrences(of: EngineerMessage.sentenceSeparator, with: " ")
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
