# UDP Binary Parsing: F1 2025 Telemetry Protocol

## How the Game Broadcasts Telemetry

F1 2025 sends real-time telemetry over UDP to a configurable IP and port. At the default 20Hz send rate, this produces ~80-100 packets/second across 16 different packet types. Each packet is a binary blob: no JSON, no protocol buffers, no framing — just raw bytes in a known layout.

## Packet Structure

Every packet starts with a common **29-byte header** followed by a type-specific payload. All data is **little-endian** and **packed with no padding**.

```
Offset  Size  Field                    Type
0       2     packetFormat             uint16   (2025)
2       1     gameYear                 uint8    (25)
3       1     gameMajorVersion         uint8
4       1     gameMinorVersion         uint8
5       1     packetVersion            uint8
6       1     packetId                 uint8    (0-15, identifies packet type)
7       8     sessionUID               uint64
15      4     sessionTime              float
19      4     frameIdentifier          uint32
23      4     overallFrameIdentifier   uint32
27      1     playerCarIndex           uint8
28      1     secondaryPlayerCarIndex  uint8
```

The `packetId` field (byte 6) determines how to interpret the remaining payload. There are 16 packet types, from Motion (0) to LapPositions (15).

## The Java Unsigned Types Problem

Java has no unsigned integer types. The F1 protocol uses `uint8`, `uint16`, `uint32`, and `uint64` extensively. Reading a `uint8` as a Java `byte` gives values -128 to 127 instead of 0 to 255.

The solution uses Java's unsigned conversion methods:

```java
// uint8 → int (0-255)
int gameYear = Byte.toUnsignedInt(buf.get());

// uint16 → int (0-65535)
int packetFormat = Short.toUnsignedInt(buf.getShort());

// uint32 → long (0-4294967295)
long frameIdentifier = Integer.toUnsignedLong(buf.getInt());

// uint64 → long (treated as signed, but bit pattern is correct)
long sessionUID = buf.getLong();
```

The `sessionUID` is technically unsigned 64-bit, but since Java's `long` is signed, values with the high bit set will appear negative. This is fine for equality comparisons and hex formatting, but would be wrong for arithmetic. In practice, sessionUID is a random identifier, not a number to do math on.

## ByteBuffer Configuration

Two critical settings:

1. **Byte order:** `buf.order(ByteOrder.LITTLE_ENDIAN)` — x86/x64 architectures and the F1 game use little-endian. Java's `ByteBuffer` defaults to big-endian. Forgetting this produces garbage values for any multi-byte field.

2. **Wrapping:** `ByteBuffer.wrap(data, 0, length)` — the `DatagramPacket` buffer may be larger than the actual packet. The `length` parameter from `packet.getLength()` gives the real size.

## Sector-Transition Detection

The ingestion system doesn't store every packet. It monitors the `sector` field in LapData packets (sent every 2 ticks at 20Hz = 10 times/second). When the `sector` value changes for a given car (0→1, 1→2, 2→0), that triggers a snapshot of all current state for that car into a database row.

This pattern — continuous state updates with event-triggered persistence — reduces ~100 packets/second down to ~60 rows per lap (3 sectors x 20 cars).

## Packet Sizes

Different packet types have different sizes, reflecting the data they carry:

| Type | Size (bytes) | Content |
|------|-------------|---------|
| Event | 45 | Single event (safety car, penalty, etc.) |
| Session | 753 | Track, weather, session config |
| FinalClassification | 1,020 | End-of-session results |
| CarDamage | 1,041 | Tyre wear, wing/floor/engine damage x22 cars |
| LapData | 1,285 | Lap times, positions, gaps x22 cars |
| CarTelemetry | 1,352 | Speed, throttle, brake, temps x22 cars |
| SessionHistory | 1,460 | Per-lap validated times for one car |

Arrays hold up to 22 car slots (20 active in the 2025 season). Downstream code must filter inactive slots.

## Key Takeaway

Binary protocol parsing in Java is straightforward once you handle little-endian byte order and unsigned types. The main pitfall is forgetting `ByteOrder.LITTLE_ENDIAN` — everything will parse without errors but produce wrong values. The conversion methods (`Byte.toUnsignedInt`, `Short.toUnsignedInt`, `Integer.toUnsignedLong`) are the standard Java pattern for this.
