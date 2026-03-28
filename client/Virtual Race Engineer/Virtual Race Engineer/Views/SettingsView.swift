import SwiftUI

struct SettingsView: View {
    @Environment(SpeechService.self) private var speech

    var body: some View {
        @Bindable var speech = speech

        NavigationStack {
            Form {
                Section("Speech") {
                    VStack(alignment: .leading) {
                        Text("Speech Rate")
                        Slider(value: $speech.rate, in: 0.3...0.6, step: 0.02) {
                            Text("Rate")
                        } minimumValueLabel: {
                            Text("Slow").font(.caption2)
                        } maximumValueLabel: {
                            Text("Fast").font(.caption2)
                        }
                    }

                    VStack(alignment: .leading) {
                        Text("Volume")
                        Slider(value: $speech.volume, in: 0.0...1.0, step: 0.05) {
                            Text("Volume")
                        } minimumValueLabel: {
                            Image(systemName: "speaker.fill").font(.caption2)
                        } maximumValueLabel: {
                            Image(systemName: "speaker.wave.3.fill").font(.caption2)
                        }
                    }
                }

                Section("Test") {
                    Button("Play Sample Message") {
                        speech.speak("Box this lap. Box, box. Switch to hard tyres.", priority: .HIGH)
                    }
                }

                Section("Info") {
                    LabeledContent("Voice", value: "English (UK)")
                    LabeledContent("Engine", value: "AVSpeechSynthesizer")
                }
            }
            .navigationTitle("Settings")
        }
    }
}
