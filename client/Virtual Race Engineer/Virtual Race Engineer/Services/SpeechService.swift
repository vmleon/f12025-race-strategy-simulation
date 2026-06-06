import AVFoundation
import Observation

@Observable
@MainActor
final class SpeechService: NSObject, AVSpeechSynthesizerDelegate {
    var rate: Float = 0.48
    var volume: Float = 1.0

    private let synthesizer = AVSpeechSynthesizer()
    private var pendingUtterances: [AVSpeechUtterance] = []
    private var isSpeaking = false

    override init() {
        super.init()
        synthesizer.delegate = self
        configureAudioSession()
    }

    func speak(_ text: String, priority: MessagePriority) {
        // The backend marks sentence boundaries with "|" (it inserts it only at real
        // boundaries, never inside decimals). Speaking each sentence as its own
        // utterance gives a natural pause between them.
        let sentences = text
            .components(separatedBy: EngineerMessage.sentenceSeparator)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { s in
                !s.isEmpty && s.unicodeScalars.contains { CharacterSet.letters.contains($0) }
            }
        guard !sentences.isEmpty else { return }

        let utterances = sentences.map(makeUtterance)

        if priority == .IMMEDIATE {
            // Flush whatever's queued/speaking and start this message now.
            synthesizer.stopSpeaking(at: .word)
            pendingUtterances = Array(utterances.dropFirst())
            synthesizer.speak(utterances[0])
            isSpeaking = true
        } else if isSpeaking {
            pendingUtterances.append(contentsOf: utterances)
        } else {
            pendingUtterances.append(contentsOf: utterances.dropFirst())
            synthesizer.speak(utterances[0])
            isSpeaking = true
        }
    }

    private func makeUtterance(_ sentence: String) -> AVSpeechUtterance {
        let utterance = AVSpeechUtterance(string: sentence)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-GB")
        utterance.rate = rate
        utterance.volume = volume
        utterance.pitchMultiplier = 1.0
        utterance.preUtteranceDelay = 0.1
        return utterance
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        pendingUtterances.removeAll()
        isSpeaking = false
    }

    // MARK: - AVSpeechSynthesizerDelegate

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            MainActor.assumeIsolated {
                self.speakNext()
            }
        }
    }

    private func speakNext() {
        guard !pendingUtterances.isEmpty else {
            isSpeaking = false
            return
        }
        let next = pendingUtterances.removeFirst()
        synthesizer.speak(next)
    }

    private func configureAudioSession() {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
            try audioSession.setActive(true)
        } catch {
            // Audio session configuration failed — TTS will still attempt playback
        }
    }
}
