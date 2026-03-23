# Research Challenges — Investigation Plan & Status Tracker

This document tracks the 8 open research questions from `design/CHALLENGES.md` that could reshape the calibration and simulation approach. These are not implementation tasks — they require accumulated session data and experimentation.

## Status Overview

| # | Challenge | Difficulty | Status | Depends On | Earliest Investigation |
|---|-----------|------------|--------|------------|----------------------|
| 1 | Additive vs Multiplicative Model | Medium | Open | Calibration pipeline (todo 12), 5+ sessions | After calibration runs |
| 2 | Game Settings Sensitivity | Medium | Open | Simulation engine (todo 14) | After simulation runs |
| 3 | State Space at Per-Sector Granularity | Medium | Open | Simulation engine (todo 14) | After simulation runs |
| 4 | AI vs Player Physics Divergence | Difficult | Open | Data ingestion (todos 07, 08), a few sessions | After first sessions ingested |
| 5 | Fuel vs Tyre Multicollinearity | Difficult | Open | Calibration pipeline (todo 12), 5+ sessions | After calibration runs |
| 6 | Determinism Hypothesis | Difficult | Open | Calibration pipeline (todo 12), 5+ sessions | After calibration runs |
| 7 | Dirty Air: Game vs Reality | Difficult | Open | Data ingestion (todos 07, 08), a few sessions | After first sessions ingested |
| 8 | Damage-Tyre Interaction | Difficult | Open | Data ingestion, 20+ sessions with damage events | Long-term |

## Investigation Order

### Wave 1 — First sessions ingested (requires todos 07, 08, 10)

**Challenge 4: AI vs Player Physics Divergence**
- Compare `tyresWear` deltas per lap between AI and player at same tyre age
- Check AI `tyresSurfaceTemperature` / `tyresInnerTemperature` realism
- Compare fuel consumption (`fuelInTank` delta) AI vs player
- Compare sector time response to damage events
- Outcome determines single vs dual calibration model

**Challenge 7: Dirty Air Model**
- Plot sector time vs `deltaToCarInFront` for AI cars
- Check consistency across tracks
- Check if player and AI experience dirty air differently
- Find the gap threshold where dirty air becomes negligible
- Outcome shapes the inter-car interaction model in Monte Carlo

### Wave 2 — After calibration pipeline runs on 5+ sessions (requires todo 12)

**Challenge 1: Additive vs Multiplicative Model**
- Fit additive model, compute residuals
- Check if residuals correlate with base pace
- Fit log-linear model, compare R² and residual structure
- Outcome may change the model form in `CALIBRATION.md`

**Challenge 5: Fuel vs Tyre Multicollinearity**
- Cross-stint comparison at matching tyre age / different fuel load
- Measure fuel burn rate from `fuelInTank` deltas, subtract analytically
- Two-stage regression: fuel from cross-stint, then tyre within-stint
- Outcome determines reliability of tyre deg coefficients

**Challenge 6: Determinism Hypothesis**
- Examine residual variance and distribution after fitting all knobs
- Compare residual variance between player and AI data
- Run identical scenarios multiple times if residuals are large
- Target: < 0.1s std dev (player), < 0.05s (AI)
- Outcome sets the noise floor for prediction accuracy

### Wave 3 — After simulation engine produces results (requires todo 14)

**Challenge 2: Game Settings Sensitivity**
- Run identical scenarios at different AI difficulty levels
- Test damage simulation mode (Full vs Reduced) effects
- Test tyre wear simulation mode scaling
- Outcome: single calibration with adjustment factors vs per-profile calibration

**Challenge 3: State Space at Per-Sector Granularity**
- Compare per-sector vs per-lap simulation position predictions
- Profile memory and CPU usage for per-sector simulation
- Outcome: keep per-sector or simplify to per-lap

### Wave 4 — Long-term (20+ sessions with damage events)

**Challenge 8: Damage-Tyre Interaction**
- Compare `tyresWear` delta per lap before/after damage events within same stint
- Assess if interaction is significant enough to model
- Test if interaction exists for AI cars or only player
- Outcome: add cross-term to model or keep independent effects

## Design Documents Referenced

- `design/CHALLENGES.md` — full problem descriptions and investigation approaches
- `design/CALIBRATION.md` — references Challenges 1, 6 (model form, determinism)
- `design/MONTECARLO.md` — references Challenges 3, 8 (state space, damage-tyre interaction)

## POC Defaults (Before Investigation)

Until each challenge is investigated, the system uses these defaults:

| Challenge | Default Assumption |
|-----------|-------------------|
| 1. Model form | Additive (simpler, switch if residuals show patterns) |
| 2. Settings | Single calibration per settings profile |
| 3. Granularity | Per-sector simulation (fall back to per-lap if needed) |
| 4. AI vs Player | Dual calibration for tyre deg only, shared for other knobs |
| 5. Fuel/Tyre | Two-stage regression with known fuel burn rate subtraction |
| 6. Determinism | Assumed deterministic within each regime |
| 7. Dirty Air | Empirical piecewise function (no real-F1 assumptions hardcoded) |
| 8. Damage-Tyre | Independent effects (no cross-term until evidence found) |
