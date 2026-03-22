# UDP Packet Loss and Recovery Strategies

## The Problem

UDP does not guarantee delivery, ordering, or deduplication. Packets can be lost, arrive out of order, or be duplicated. For a telemetry system that captures data at sector transitions (every ~20-30 seconds per car), a single lost packet can mean a missing data point that affects calibration and simulation quality.

The critical packet is LapData (sent 10 times/second at 20Hz). The ingestion system watches the `sector` field in LapData for each car — when it changes (0→1, 1→2, 2→0), that transition triggers a database write. If the specific LapData packet where the transition happens is lost, the primary capture is missed.

## How Likely Is Packet Loss?

On a local network (game console → same machine or LAN), loss rates are typically near zero. But under load — if the game is sending at 60Hz, or the ingestion server is busy with a database write — packets can be dropped at the OS socket buffer level.

At 20Hz default send rate, LapData arrives every ~100ms. Sector transitions happen every ~20-30 seconds. The window where loss matters is the 1-2 packets where the sector value changes. Missing 1 out of ~200-300 LapData packets between transitions loses that sector snapshot.

## Tiered Recovery Strategy

### Tier 1: LapData cumulative times (fastest recovery)

Even if the transition packet is lost, the **next** LapData packet for that car will show the updated sector value and still contain the cumulative sector time fields (`sector1TimeInMS`, `sector2TimeInMS`). These fields persist across packets — they aren't cleared until the next lap starts.

If a gap is detected (sector jumps by more than 1 step, e.g., 0→2 instead of 0→1→2), the system uses these cumulative times to reconstruct the missed sector row.

This is the fastest recovery path since LapData arrives frequently (~10 times/second).

### Tier 2: SessionHistory sliding window (fallback)

SessionHistory (packet type 11) contains game-validated per-lap and per-sector times for a specific car. It's sent every 2 ticks, cycling through cars. The ingestion system maintains an in-memory buffer of the last 5 laps per car from SessionHistory data.

If Tier 1 recovery fails (multiple consecutive packets lost), SessionHistory provides an authoritative source of sector times. This is slower (the packet cycles through 22 cars, so a specific car's update arrives every ~2 seconds) but more complete.

**Memory cost is bounded:** 22 cars x 5 laps x sector data = trivial.

### Tier 3: Gap flagging (last resort)

If neither Tier 1 nor Tier 2 can fill the gap, the sector row is written with a `recovered` flag:

| Value | Meaning |
|-------|---------|
| 0 | Primary capture (sector transition detected normally) |
| 1 | Recovered from LapData cumulative times |
| 2 | Recovered from SessionHistory buffer |
| 3 | Gap flagged — incomplete data |

The simulation and calibration pipeline use this flag to weight data by confidence. Primary captures are highest confidence. LapData-recovered is good. SessionHistory-recovered is acceptable. Flagged gaps are excluded or downweighted.

## Edge Cases

**Multiple consecutive lost packets:** If 3+ LapData packets are lost in a row (within the ~1 second between sector transitions), both primary detection and Tier 1 recovery fail. At 20Hz, this means 3+ consecutive losses — rare but possible under heavy system load.

**Race start (lap 1):** Standing start, cold tyres, frequent collisions. Sector times from lap 1 are excluded from calibration anyway but must be tracked correctly for position tracking.

**Session changes:** When `sessionUID` changes, all in-memory state (latest car state, SessionHistory buffers) must be reset.

## Natural Composite PK as Deduplication

The database uses a natural composite primary key on `sector_snapshots`: `(session_uid, car_index, lap_number, sector_number)`. There is exactly one sector completion per car per sector per lap. If a recovered sector row arrives after the primary one, the PK constraint catches the duplicate. This makes writes idempotent without extra deduplication logic.

## Key Takeaway

UDP loss is a data quality problem, not a reliability problem. The system doesn't need guaranteed delivery — it needs to know when data is missing and how confident it should be in what it has. The tiered recovery + quality flag approach handles this without adding protocol complexity (no ACKs, no retransmission, no TCP).
