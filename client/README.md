# Virtual Race Engineer (iOS Client)

SwiftUI app that connects to the Backend via WebSocket and speaks race engineer messages aloud using text-to-speech.

## Requirements

- **Xcode 26.3** or later
- **iOS 26.2** minimum deployment target
- Swift 5.0 (bundled with Xcode)
- No external dependencies (SPM packages are not required)

## Opening the Project

```
open client/Virtual\ Race\ Engineer/Virtual\ Race\ Engineer.xcodeproj
```

## Signing Configuration

The project uses **automatic signing** (`CODE_SIGN_STYLE = Automatic`).

1. Open the project in Xcode
2. Select the **Virtual Race Engineer** target
3. Go to **Signing & Capabilities**
4. Select your **Team** from the dropdown (personal or organization Apple Developer account)
5. Xcode will generate a provisioning profile automatically

> For running on the **simulator**, no Apple Developer account is needed.
> For running on a **physical device**, a free or paid Apple Developer account is required.

If you see a signing error, change the **Bundle Identifier** (`dev.victormartin.Virtual-Race-Engineer`) to something unique to your team.

## Configuring the Backend URL

The backend URL is configured **at runtime** inside the app, not at build time.

When the app launches, the **Connect** screen asks for:

- **Backend URL** — defaults to `http://localhost:8080`. Change this to your machine's IP (e.g., `http://192.168.1.50:8080`) when running on a physical device or a simulator that cannot reach `localhost`.
- **Session UID** — the active telemetry session identifier.

These values are persisted between launches via `@AppStorage`.

## Running on the Simulator

1. In Xcode, select an iPhone or iPad simulator from the device dropdown (e.g., **iPhone 16 Pro**)
2. Press **Cmd+R** (or click the Run button)
3. The app will build and launch in the simulator
4. On the Connect screen, keep the default `http://localhost:8080` (the simulator shares the Mac's network) and enter a valid Session UID
5. Tap **Connect**

> **Note:** The simulator supports text-to-speech via `AVSpeechSynthesizer`, but audio output may sound different from a real device.

## Running on a Physical Device

1. Connect your iPhone or iPad via USB (or configure wireless debugging in **Window > Devices and Simulators**)
2. Select your device from the Xcode device dropdown
3. Ensure signing is configured (see above)
4. Press **Cmd+R**
5. On first run, you may need to trust the developer certificate on the device: **Settings > General > VPN & Device Management**
6. On the Connect screen, enter your Mac's local IP address instead of `localhost` (e.g., `http://192.168.1.50:8080`). Both the phone and the Mac must be on the same network.
7. Enter the Session UID and tap **Connect**

## App Overview

| Screen       | Purpose                                                                                  |
| ------------ | ---------------------------------------------------------------------------------------- |
| **Connect**  | Enter backend URL and session UID, then connect                                          |
| **Live**     | Displays incoming race engineer messages in real time; messages are spoken aloud via TTS |
| **Settings** | Adjust speech rate and volume; play a sample message                                     |

## Troubleshooting

| Problem                    | Solution                                                                                                                         |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Cannot connect to backend  | Verify the backend is running (`cd backend && ./gradlew bootRun`). On a physical device, use the Mac's LAN IP, not `localhost`.  |
| No messages appearing      | Confirm the Session UID matches an active telemetry session. Check the WebSocket status indicator at the top of the Live screen. |
| No audio / TTS not working | Open **Settings** in the app, tap **Play Sample Message**. On simulator, ensure your Mac's volume is up.                         |
| Signing error              | Change the Bundle Identifier to one unique to your Apple Developer team.                                                         |
