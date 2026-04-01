import Foundation

struct ActiveSession: Decodable, Identifiable {
    let sessionUid: String
    let trackName: String
    let sessionType: String

    var id: String { sessionUid }
}
