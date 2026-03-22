# Design Lessons Learned

This document captures the key insights discovered during the system design phase of the F1 2025 race strategy simulation project.

## Monte Carlo Is Not Enough

The initial assumption was that a Monte Carlo simulation engine alone could predict race outcomes. In practice, Monte Carlo is **dumb sampling** — it draws from whatever distributions and coefficients you give it. It does not learn, adjust, or self-correct.

A working system requires three distinct pipelines:

1. **Ingestion** — Capture real-time UDP telemetry from the F1 2025 game and persist it to a database. The game emits ~80-100 packets/second across 16 packet types. Only a fraction needs to be stored: one snapshot per car per sector completion (~60 rows per lap for 20 cars), plus discrete events and session metadata.

2. **Calibration** — Fit model coefficients ("knobs") from the accumulated historical data. The lap time model has ~10 knobs (tyre degradation, fuel effect, dirty air, DRS advantage, damage effects, weather, etc.), each requiring enough observed data points to produce reliable fitted values. Without calibration, the simulation would rely on hardcoded guesses with wide, uninformed distributions.

3. **Simulation** — The Monte Carlo engine itself. It takes the fitted coefficients from calibration and runs thousands of iterations, sampling from residual noise distributions, to project sector-by-sector race outcomes under different strategy choices.

```
[Game UDP] → [Ingestion] → [Database] → [Calibration] → [Fitted Coefficients] → [Monte Carlo]
```

The quality of predictions depends entirely on the quality of calibration, which depends entirely on the volume and quality of ingested data. Monte Carlo without calibration is just random number generation with extra steps.

## AI Cars vs Player Car: Two Different Physics Models

F1 2025 runs **different physics models** for AI-controlled cars and the player car. Community testing and EA forum reports strongly suggest that AI cars use a simplified, pre-programmed pace reduction to simulate tyre wear, rather than the full tyre physics applied to the player.

This has a critical implication: a single unified calibration model fitted to all 20 cars describes **neither** system accurately. The calibration pipeline must maintain two separate sets of coefficients:

- **Player coefficients** — fitted from the player car's telemetry only (1 car per session). Reflects the game's full physics: tyre wear, temperature, fuel, damage.
- **AI coefficients** — fitted from the 19 AI cars' telemetry. Reflects the game's simplified AI pace model, which may show more linear and predictable degradation patterns.

When the simulation predicts a race, it must use player coefficients for the player's car and AI coefficients for the opponents. Using player-fitted tyre degradation curves for AI cars would overestimate their pace drop-off, producing overly optimistic strategy predictions.

The tradeoff: player coefficients accumulate data 19x slower (1 car vs 19). Some player-specific knobs (damage effects, weather impact) may never have enough data and will need to rely on defaults for a long time.

## Per-Sector Granularity Over Per-Lap

The simulation operates at per-sector granularity rather than per-lap. This is 3x more data but captures effects that are sector-specific:

- Overtakes happen in specific sectors (DRS zones, heavy braking points)
- A position change in sector 1 changes dirty air and DRS dynamics for sectors 2 and 3
- DRS zones exist in specific sectors, so sector-level data models which sectors allow overtaking

This produces ~60 rows per lap (3 sectors x 20 cars) — still very manageable.

## Capture Strategy: Snapshot, Don't Stream

The system does not store every incoming packet. Instead, it maintains an in-memory "latest state" per car updated with every packet, and takes a **snapshot** only when a sector change is detected (the `sector` field transitions in LapData). This reduces ~80-100 packets/second down to ~60 persisted rows per lap.

Recovery from UDP packet loss uses a tiered approach: first try to reconstruct from the next LapData packet's cumulative sector times, then fall back to SessionHistory buffers kept in-memory as a sliding window.
