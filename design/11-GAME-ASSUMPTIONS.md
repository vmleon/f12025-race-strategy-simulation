# Game Assumptions

This document tracks F1 2025 game-imposed constants that the project
assumes to be true. Values marked **unverified** should be checked in-game
when the opportunity arises and updated here.

## Tyre Compound Lifespan

Expected number of laps before a compound hits the performance cliff.
Past this point a driver typically drops multiple positions quickly.
Used by the simulator for stint-length projection and by the strategy
candidate generator to prune infeasible candidates.

| Compound | Lifespan (laps) | Source                                                  |
| -------- | --------------- | ------------------------------------------------------- |
| Soft     | 30              | Observed in-game                                        |
| Medium   | 37              | Observed in-game                                        |
| Hard     | 45 (unverified) | Extrapolated (Soft + 7 × 2). **TODO: confirm in-game.** |
| Inter    | — (unverified)  | **TODO: observe and record.**                           |
| Wet      | — (unverified)  | **TODO: observe and record.**                           |

## Open Questions

- Hard compound lifespan — extrapolated, not observed.
- Intermediate and wet lifespans — unknown.
- Cliff behaviour: how steep is the performance drop past the lifespan?
  Currently modeled as a hard pit trigger (simulator) and a hard candidate
  cutoff (candidate generator), not as graceful degradation.
