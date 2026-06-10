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

## Pit Status in Snapshots

`pit_status` in `sector_snapshots` is only ever observed as **0** (on track) or **1**
(in the pit lane / pitting). The game's value **2** ("in pit area", being serviced) is
effectively **never recorded**: snapshots are written on sector transitions, and the
stationary box stop plus pit exit fall inside the out-lap's first sector — which the game
has already flagged back to `pit_status = 0` by the time that sector completes. The
practical consequence is that a pit stop's real time loss (~20 s) lands mostly in a
`pit_status = 0` out-lap sector, not the `pit_status = 1` entry sector, so the pit-loss
calibration walks forward into the inflated out-lap sector(s) (see `05-CALIBRATION.md`).

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
