import AVFoundation
import Observation

@Observable
final class SpeechService: NSObject, @unchecked Sendable, AVSpeechSynthesizerDelegate {
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
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-GB")
        utterance.rate = rate
        utterance.volume = volume
        utterance.pitchMultiplier = 1.0
        utterance.preUtteranceDelay = 0.1

        if priority == .IMMEDIATE {
            synthesizer.stopSpeaking(at: .word)
            pendingUtterances.removeAll()
            synthesizer.speak(utterance)
            isSpeaking = true
        } else if isSpeaking {
            pendingUtterances.append(utterance)
        } else {
            synthesizer.speak(utterance)
            isSpeaking = true
        }
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        pendingUtterances.removeAll()
        isSpeaking = false
    }

    // MARK: - AVSpeechSynthesizerDelegate

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            self.speakNext()
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
