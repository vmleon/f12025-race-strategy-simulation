# Cold Start Problem and Default Values

## The Problem

The Monte Carlo simulation needs fitted coefficients for ~10 knobs (tyre degradation, fuel effect, dirty air, DRS advantage, damage effects, etc.) before it can produce meaningful predictions. But when the system starts, there is zero historical data. How do you bootstrap a simulation that depends on calibration when there's nothing to calibrate from?

## The Approach: Progressive Replacement

Start with reasonable default values for every knob. These are educated guesses, **not** derived from real F1 physics — the game is under no obligation to match reality. As data accumulates, replace each default with a fitted value once enough data points exist.

Each coefficient is stored with:
- The fitted value (or default)
- A confidence indicator (standard error or data point count)
- An `is_default` flag (using a guess vs. a fitted value)
- The number of sessions used in fitting

The simulation uses wider variance when sampling from defaults (low confidence) and tighter variance when using fitted values (high confidence).

## Initial Default Values

| Knob | Default | Confidence | Notes |
|------|---------|------------|-------|
| Tyre deg (soft) | +0.05s/lap/sector | Low | Highly track-dependent; AI likely lower |
| Tyre deg (medium) | +0.03s/lap/sector | Low | Highly track-dependent; AI likely lower |
| Tyre deg (hard) | +0.02s/lap/sector | Low | Highly track-dependent; AI likely lower |
| Fuel effect | +0.01s/kg/sector | Medium | May be similar for player and AI |
| Front wing damage | +0.02s/percent/sector | Low | Depends on sector type |
| Floor damage | +0.04s/percent/sector | Low | Ground effect cars very sensitive |
| Engine damage | +0.01s/percent/sector | Low | Mainly affects straights |
| Dirty air (< 1s gap) | +0.3s/sector | Low | Game model unknown |
| DRS advantage | -0.2s/sector (DRS sectors) | Medium | Track-dependent |
| Overtake probability | 0.15 per sector boundary | Low | Very track-dependent |
| Safety car rate | 0.01 per lap | Low | Depends on game settings |

## Sample Size Estimates: When Do Defaults Get Replaced?

| Knob | Sessions per track needed | Why |
|------|--------------------------|-----|
| Base pace | ~3-5 | Many data points per session (3 sectors x ~50 laps) |
| Tyre deg per compound | ~5-10 per compound | Need enough stints on each compound |
| Fuel effect | ~5 | Can use cross-stint data |
| Damage effects | ~20+ with damage incidents | Most sessions have zero damage — sparse |
| Dirty air | ~5-10 | Many data points from AI cars following each other |
| DRS | ~3-5 | Straightforward matched comparison |
| Weather | ~50+ | Weather variation is rare unless configured |
| Overtake probability | ~10-20 | Need enough position changes |

## The Asymmetry: Player vs AI Data Rates

The dual calibration requirement (see `DESIGN.md`) creates a severe asymmetry:

- **AI coefficients** accumulate data from 19 cars per session. Dirty air, DRS, and base pace converge quickly because there are many AI-vs-AI interactions every race.
- **Player coefficients** come from 1 car per session. Tyre degradation needs multiple stints per compound. Damage effects need damage incidents involving the player car specifically.

Some player knobs — damage effects, weather impact — may never have enough data within a reasonable number of sessions. These will rely on defaults for a long time, or fall back to AI-fitted values with an adjustment factor.

## Key Takeaway

A cold start is not a blocker — it's a gradient. The simulation starts rough (wide distributions, low confidence) and progressively improves as data flows in. The important design choice is making the transition from defaults to fitted values automatic and transparent, with confidence indicators that tell the simulation how much to trust each coefficient.
