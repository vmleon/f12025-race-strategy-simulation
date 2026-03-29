# Notes

## General notes

- Each topic should consider @design/ and @todos/
- Review the code
- Consider what documents will need update
- The desired outcome is not to implement but to create a new @todos/ item and place it in the best order to implement. Rename todos accordingly to reflect the best order.
- Do not implement, just the markdown todo item.

## Decisions

### iOS Client: WebSocket over APNs (2026-03-28)

For the iOS voice client (todo 14), chose **WebSocket** (Option B) over APNs:

- **APNs requires paid Apple Developer Program** ($99/year) — not justified for a PoC
- **Background TTS via APNs is unreliable** — `UNNotificationServiceExtension` has ~30s execution limit, and iOS restricts audio playback from extensions
- **WebSocket endpoint already exists** — backend exposes `/ws/race-engineer` with the exact JSON payload needed
- **Message volume is low** (~few per minute) — no scalability concern that would favor push notifications
- **Tradeoff:** app must be in foreground to receive messages. Acceptable for a driver using the phone as a dedicated race engineer device.

## Topics

### Clean up Liquibase

We are in develop mode, so we can clean up alter tables and just create the final version of the schema with liquibase without versioning. A few files with the current structure is good, but no need to keep it in layers at this point.