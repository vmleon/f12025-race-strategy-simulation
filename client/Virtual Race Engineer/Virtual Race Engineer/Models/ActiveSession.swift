import Foundation

struct ActiveSession: Decodable, Identifiable {
    let sessionUid: String
    let trackName: String
    let sessionType: String
    let live: Bool

    var id: String { sessionUid }
}
