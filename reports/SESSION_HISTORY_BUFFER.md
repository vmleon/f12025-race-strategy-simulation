# SessionHistoryBuffer — Ring Buffer for UDP Loss Recovery

## Problem

The F1 2025 game sends telemetry over UDP, which is inherently lossy. Sector transition detection happens by observing changes in the `sector` field across consecutive LapData packets. If a packet is dropped at the exact moment a car crosses a sector boundary, the transition is missed — there is no retransmission.

Without recovery, a missed sector transition means a gap in `sector_snapshots`. Calibration and simulation downstream depend on continuous sector time data. Even a few missing rows can skew statistical fits or leave gaps in race replay.

## Why a Ring Buffer

The game sends a separate packet type — **SessionHistory (packet ID 11)** — that contains cumulative, game-validated sector times for all completed laps of a single car. This packet arrives periodically (roughly once per second per car) and is the game's own record of sector times, independent of our transition detection.

The key insight: SessionHistory packets are redundant with data we already capture via sector transitions, but they arrive on a different cadence. If we miss the LapData transition, there is a good chance the SessionHistory packet for that lap has already been buffered or will arrive shortly after.

A ring buffer is the right structure because:

1. **Bounded memory** — A 22-driver race with 5 laps of history = 110 entries. Fixed allocation, no GC pressure, predictable memory footprint during high-frequency UDP processing.
2. **Only recent laps matter** — If a sector was missed more than ~5 laps ago and we still do not have it, it is a genuine gap. Holding older data would not help recovery.
3. **O(1) lookup** — `lap % BUFFER_LAPS` gives the ring index directly. No scanning, no hash map overhead.
4. **Overwrite semantics** — Old laps are silently replaced as new ones arrive. No explicit eviction logic needed.

## Structure

```
buffer[22][5]  — SessionHistoryData.LapHistory per car, per ring slot
latestLap[22]  — most recent lap number stored per car
```

Each `LapHistory` entry contains three sector times in milliseconds plus validity flags, as provided by the game.

The ring index for any lap is `(lapNumber - 1) % 5`. The `latestLap` array allows a staleness check: if the requested lap is more than 5 behind `latestLap[car]`, the slot has been overwritten and the lookup returns -1.

## How It Fits in the Recovery Tiers

Recovery is tiered by data quality:

| Tier | Source | `recovery_source` | Confidence |
|------|--------|--------------------|------------|
| 0 | Primary — sector transition detected normally from LapData | 0 | Highest |
| 1 | LapData cumulative sector times still present in the current packet | 1 | High |
| 2 | SessionHistoryBuffer lookup | 2 | Acceptable |
| 3 | Gap flagged — no data available | 3 | Excluded from calibration |

`SectorTransitionDetector.recoverTier()` walks through these tiers in order. The buffer is consulted only when the LapData packet itself does not contain usable sector times for the missed transition.

## Data Flow

```
Game → UDP packet (type 11: SessionHistory)
    → App.java parses SessionHistoryData
    → historyBuffer.update(history)     ← stores last 5 laps per car

Game → UDP packet (type 6: LapData)
    → SectorTransitionDetector.detect(laps, historyBuffer)
        → if sector skipped:
            → recoverTier() checks LapData first (Tier 1)
            → then historyBuffer.getSectorTime() (Tier 2)
            → then flags as gap (Tier 3)
    → captureSnapshot(..., historyBuffer, ...)
        → resolveSectorTime() reads buffer for Tier 2 snapshots
```

The buffer is reset on session change (`sessionUID` transition) since lap numbering restarts.

## Why 5 Laps

Five laps is a pragmatic choice:

- **UDP loss is bursty but short** — network congestion or OS buffer overflow typically drops packets for fractions of a second, not minutes. A missed transition is almost always recoverable within the next few seconds as new SessionHistory packets arrive.
- **SessionHistory packets arrive frequently** — roughly once per second per car. Even if a few are lost, a 5-lap window means the data for any recent lap has been written to the buffer multiple times.
- **Memory efficiency** — 22 cars x 5 laps x ~16 bytes per LapHistory = ~1.7 KB total. Negligible.
- **Diminishing returns beyond 5** — if a sector time was not recovered within 5 laps (~6-8 minutes of real racing), it is better to flag it as a gap than to hope for late recovery.

## Downstream Impact

The `recovery_source` column in `sector_snapshots` propagates through the entire pipeline:

- **Calibration** excludes Tier 3 (gaps) and can optionally downweight Tier 2 recoveries
- **Simulation** uses the confidence metadata when weighting historical data
- **Portal** can display recovery status for transparency

This makes the ring buffer a small but load-bearing component: it directly reduces the gap rate in persisted telemetry data, which improves calibration sample sizes and coefficient quality.
