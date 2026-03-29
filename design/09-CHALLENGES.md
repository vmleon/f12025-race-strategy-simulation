# Challenges — Open Research Questions

This document tracks the hard problems in the calibration and simulation pipelines that require investigation, testing, or data before they can be resolved. Referenced from `05-CALIBRATION.md` and `03-MONTECARLO.md`. See the Investigation Plan section at the end for status and phased investigation order.

Manageable challenges (sample size requirements, overtake filtering, defending modeling, Monte Carlo convergence, simulation re-trigger frequency, UDP loss recovery) have been resolved into actionable measures within `05-CALIBRATION.md` and `03-MONTECARLO.md` respectively.

---

## Medium Difficulty

These require experimentation but have tractable solutions — the test is clear and either outcome has a defined next step.

### 1. Additive vs Multiplicative Lap Time Model (Calibration)

**Problem:** The current model adds time deltas: `sector_time = base_pace + tyre_deg + fuel_effect + ...`. But in real physics (and likely in the game), aero effects are percentage-based downforce losses. A car with 10% less downforce loses more time on a high-downforce track than a low-downforce one. This would be better modeled as:

```
sector_time = base_pace * (1 + tyre_factor) * (1 + fuel_factor) * (1 + damage_factor) * ...
```

Or equivalently, in log space:

```
log(sector_time) = log(base_pace) + log(1 + tyre_factor) + ...
```

**How to test:**

1. Fit the additive model, compute residuals
2. Check if residuals correlate with base pace — if damage costs more time on faster sectors, the effect is multiplicative
3. Fit a log-linear model on the same data, compare R² and residual structure
4. If multiplicative fits better, switch the model form

**Impact:** If the model form is wrong, all coefficients will be biased. Damage effects will be overestimated on slow sectors and underestimated on fast sectors.

### 2. Game Settings Sensitivity (Calibration)

**Problem:** AI difficulty, damage simulation mode, and tyre wear simulation mode all affect game physics. If a user runs calibration sessions at difficulty 90 and then races at difficulty 95, the fitted AI base pace is wrong.

**What to investigate:**

- Run identical race scenarios at different AI difficulty levels. Is the effect a simple linear offset on AI pace, or does it change the shape of other curves (tyre deg, etc.)?
- Does damage simulation mode (Full vs Reduced) change damage _rates_ only, or also damage _effects_ on pace?
- Does tyre wear simulation mode scale the degradation curve linearly, or change its shape?

**If settings effects are simple (linear scaling):** A single calibration with a settings adjustment factor may work.
**If settings effects are complex:** Separate calibrations per settings profile are needed, which multiplies data requirements.

### 3. State Space Explosion at Per-Sector Granularity (Monte Carlo)

**Problem:** The simulation tracks 20 cars, each with: position, tyre compound, tyre age, fuel load, damage levels, gap to car ahead. At each sector boundary, the simulation must resolve interactions (dirty air, DRS, overtake probability) between all adjacent cars. This creates a combinatorial state space.

**Key concerns:**

- **Overtake resolution order:** If car A can overtake car B, and car B can overtake car C, the order of resolution matters. How is this handled?
- **Cascading effects:** An overtake in sector 1 changes dirty air and DRS for sector 2. The simulation must propagate these effects correctly through the sector chain
- **Position ties:** Two cars crossing a sector boundary simultaneously — how is position determined?

**What to investigate:**

- Compare simulation results with a simpler per-lap model. If per-sector gives nearly identical position predictions, the added complexity isn't worth it
- Profile memory and CPU usage for the per-sector simulation. Does the state tracking for 20 cars × all variables fit comfortably in memory?

**For the POC:** Implement the per-sector model but fall back to per-lap if performance is unacceptable. The per-sector model's value is in modeling sector-specific overtakes — if overtake modeling is too noisy anyway, per-lap may be sufficient.

---

## Difficult

These involve fundamental unknowns that could reshape the entire approach. They require extensive data collection and the answers are not predictable in advance.

### 4. AI vs Player Physics: How Different Are They? (Calibration)

**Problem:** Community reports say AI tyre degradation is "pre-programmed pace reduction" rather than full physics simulation. But the exact scope of the difference is unknown. It could be limited to tyre deg, or it could extend to fuel, damage, dirty air, and other effects.

**What to investigate:**

- Collect telemetry from multiple sessions and compare AI vs player tyre wear rates (`tyresWear` field) at the same tyre age — do AI cars show lower wear values?
- Check if AI `tyresSurfaceTemperature` / `tyresInnerTemperature` change realistically or stay flat
- Compare AI vs player sector time response to damage events — does the same damage percentage produce the same time loss?
- Test whether AI fuel consumption (`fuelInTank` delta per lap) matches the player's

**Possible outcomes:**

- Only tyre deg differs → fit tyre deg separately, share other knobs
- Multiple systems differ → full dual calibration as described in `05-CALIBRATION.md`
- Differences are minor → a single model with an AI/player offset term may suffice

**Why this is critical:** This determines whether the calibration pipeline needs one model or two, which changes the entire architecture and data requirements downstream.

### 5. Multicollinearity: Separating Fuel from Tyre Degradation (Calibration)

**Problem:** Within a single stint, fuel load and tyre age decrease/increase at nearly constant rates per lap. A regression on within-stint data cannot reliably attribute time loss to one vs the other.

**Approaches to test:**

- **Cross-stint comparison:** Find laps where tyre age matches but fuel load differs (e.g., lap 5 of stint 1 vs lap 5 of stint 2). Requires enough sessions with varied pit strategies
- **Known fuel burn rate:** If the game's fuel consumption rate can be measured precisely (from `fuelInTank` deltas), subtract the fuel effect analytically before fitting tyre deg
- **Two-stage regression:** First estimate fuel effect from cross-stint data only, then fix it and estimate tyre deg within stints

**Data needed:** At minimum, sessions with different pit stop laps so that the fuel/tyre age correlation breaks across stints.

**Why this is critical:** If fuel and tyre effects can't be separated, every tyre degradation coefficient is contaminated with fuel effects, making strategy predictions unreliable.

### 6. Validating the "Determinism" Hypothesis (Calibration)

**Problem:** The calibration approach assumes that within each regime (player/AI), the game applies consistent formulas — same inputs produce same outputs. If the game introduces hidden randomness (e.g., random grip variation, stochastic AI behavior), the model will have irreducible noise.

**How to test:**

1. After fitting all knobs, examine residual variance. If residuals are small and normally distributed, the determinism assumption holds
2. If residuals are large or show patterns, investigate:
   - Are there missing variables? (e.g., ERS deployment mode, brake bias)
   - Is there genuine randomness? Run the same scenario multiple times (same track, same settings, same inputs) and check if outcomes vary
3. Compare residual variance between player and AI data. If AI residuals are much smaller (because their model is simpler/more predictable), that's evidence supporting the dual-model hypothesis

**Acceptable residual levels (rough target):** < 0.1s standard deviation per sector for the player model, < 0.05s for AI model.

**Why this is critical:** If the game has hidden randomness, the entire calibration approach has an irreducible noise floor that limits prediction accuracy.

### 7. Dirty Air Model: Game vs Reality (Calibration + Monte Carlo)

**Problem:** `03-MONTECARLO.md` references dirty air being "most significant within ~1–1.5 seconds." This is based on real-F1 knowledge, but:

1. The F1 25 game's dirty air model is a game-engine approximation, not a CFD simulation. It may not match real physics at all
2. The thresholds (1.5s, 3s) and magnitude (+0.3s/sector) are guesses

**What to investigate:**

- Collect sector times for AI cars at various `deltaToCarInFront` values. Plot sector time vs gap — does a clear dirty air curve emerge?
- Is the effect consistent across tracks, or does it vary with track aero characteristics?
- Does the game apply dirty air to the player car differently than AI? (relates to Challenge 4)
- What is the actual gap threshold where dirty air becomes negligible in the game?

**For the POC:** Don't hardcode real-F1 dirty air assumptions. Treat the dirty air curve as a fully empirical function fitted from observed data. Start with a simple piecewise model (e.g., linear effect below threshold, zero above).

**Why this is critical:** Dirty air is a core inter-car interaction. If the model is wrong, overtake predictions, tyre deg predictions for cars in traffic, and strategy recommendations are all affected.

### 8. Damage-Tyre Interaction (Calibration)

**Problem:** Aero damage increases tyre degradation (less downforce = more sliding = more wear). This is plausible in real F1 but unverified in the game. It introduces a cross-term into the lap time model:

```
tyre_deg_effective = tyre_deg_base + damage_tyre_interaction(damage_level, tyre_age)
```

**What to investigate:**

- Compare tyre wear rates (`tyresWear` delta per lap) before and after a damage event for the same car in the same stint. Does wear rate increase?
- If the interaction exists, is it significant enough to model? A 5% increase in tyre wear due to minor wing damage may be negligible
- Does this interaction exist for AI cars, or only for the player? (AI tyre deg may be pre-programmed regardless of damage)

**For the POC:** Start with independent damage and tyre effects (no cross-term). If residual analysis shows a pattern where damaged cars have systematically worse tyre deg than predicted, add the interaction term.

**Why this is critical:** Data scarcity makes this hard to validate — damage events are rare, and you need damage _during a stint_ to measure the cross-term. Could remain unresolved for many sessions.

---

## Investigation Plan & Status Tracker

These challenges are not implementation tasks — they require accumulated session data and experimentation. The investigation order follows data dependencies.

### Status Overview

| # | Challenge | Difficulty | Status | Depends On | Earliest Investigation |
|---|-----------|------------|--------|------------|----------------------|
| 1 | Additive vs Multiplicative Model | Medium | Open | Calibration pipeline, 5+ sessions | After calibration runs |
| 2 | Game Settings Sensitivity | Medium | Open | Simulation engine | After simulation runs |
| 3 | State Space at Per-Sector Granularity | Medium | Open | Simulation engine | After simulation runs |
| 4 | AI vs Player Physics Divergence | Difficult | Open | Data ingestion, a few sessions | After first sessions ingested |
| 5 | Fuel vs Tyre Multicollinearity | Difficult | Open | Calibration pipeline, 5+ sessions | After calibration runs |
| 6 | Determinism Hypothesis | Difficult | Open | Calibration pipeline, 5+ sessions | After calibration runs |
| 7 | Dirty Air: Game vs Reality | Difficult | Open | Data ingestion, a few sessions | After first sessions ingested |
| 8 | Damage-Tyre Interaction | Difficult | Open | Data ingestion, 20+ sessions with damage | Long-term |

### Wave 1 — First sessions ingested

**Challenge 4: AI vs Player Physics Divergence** — Compare `tyresWear` deltas, tyre temperatures, fuel consumption, and damage response between AI and player. Outcome determines single vs dual calibration.

**Challenge 7: Dirty Air Model** — Plot sector time vs `deltaToCarInFront` for AI cars. Check consistency across tracks and whether player/AI experience dirty air differently. Find the negligible-effect gap threshold.

### Wave 2 — After calibration pipeline runs on 5+ sessions

**Challenge 1: Additive vs Multiplicative Model** — Fit additive model, compute residuals, check if residuals correlate with base pace. Fit log-linear model, compare R².

**Challenge 5: Fuel vs Tyre Multicollinearity** — Cross-stint comparison, fuel burn rate subtraction, two-stage regression. Outcome determines reliability of tyre deg coefficients.

**Challenge 6: Determinism Hypothesis** — Examine residual variance after fitting all knobs. Compare player vs AI residuals. Target: < 0.1s std dev (player), < 0.05s (AI).

### Wave 3 — After simulation engine produces results

**Challenge 2: Game Settings Sensitivity** — Run identical scenarios at different AI difficulty levels. Test damage/tyre wear simulation modes.

**Challenge 3: State Space at Per-Sector Granularity** — Compare per-sector vs per-lap simulation predictions. Profile memory and CPU usage.

### Wave 4 — Long-term (20+ sessions with damage events)

**Challenge 8: Damage-Tyre Interaction** — Compare `tyresWear` delta per lap before/after damage events within same stint.

### POC Defaults (Before Investigation)

Until each challenge is investigated, the system uses these defaults:

| Challenge | Default Assumption |
|-----------|-------------------|
| 1. Model form | Additive (switch if residuals show patterns) |
| 2. Settings | Single calibration per settings profile |
| 3. Granularity | Per-sector (fall back to per-lap if needed) |
| 4. AI vs Player | Dual calibration for tyre deg only, shared for other knobs |
| 5. Fuel/Tyre | Two-stage regression with known fuel burn rate subtraction |
| 6. Determinism | Assumed deterministic within each regime |
| 7. Dirty Air | Empirical piecewise function (no real-F1 assumptions) |
| 8. Damage-Tyre | Independent effects (no cross-term until evidence found) |
