# Game Assumptions

This document tracks F1 2025 game-imposed constants that the project
assumes to be true. Values marked **unverified** should be checked in-game
when the opportunity arises and updated here.

## Tyre Compound Lifespan

Expected number of laps before a compound hits the performance cliff.
Past this point a driver typically drops multiple positions quickly.
Used by the simulator for stint-length projection and by the strategy
candidate generator to prune infeasible candidates.

The simulator no longer treats these lap counts as fixed. It derives stint
length from a **calibrated wear-rate** (`tyre_wear_rate_{soft,medium,hard}`,
%/lap) combined with a hardcoded **~40% wear cliff** (`cliffPct`):
`laps-to-cliff = 40% / wear-rate`. This makes stint length data-driven per
circuit. The table below is the **fallback** used only when the wear-rate is
uncalibrated. With the default wear-rates (Soft 1.33, Medium 1.08, Hard 0.89
%/lap) the formula reproduces these same ~30 / 37 / 45 values.

| Compound | Fallback lifespan (laps) | Source                                                  |
| -------- | ------------------------ | ------------------------------------------------------- |
| Soft     | 30                       | Observed in-game                                        |
| Medium   | 37                       | Observed in-game                                        |
| Hard     | 45 (unverified)          | Extrapolated (Soft + 7 × 2). **TODO: confirm in-game.** |
| Inter    | — (unverified)           | **TODO: observe and record.**                           |
| Wet      | — (unverified)           | **TODO: observe and record.**                           |

## Open Questions

- Hard compound lifespan — extrapolated, not observed.
- Intermediate and wet lifespans — unknown.
- Cliff behaviour: how steep is the performance drop past the cliff? The
  cliff is now modeled as a **wear-% threshold** (~40%, `cliffPct`), not a
  fixed lap count — laps-to-cliff is derived from the calibrated wear-rate.
  The threshold itself is hardcoded and tunable (it can't be learned from
  short practice runs; only the wear-rate is calibrated). It still acts as a
  hard pit trigger (simulator) and a hard candidate cutoff (candidate
  generator), not as graceful degradation.
