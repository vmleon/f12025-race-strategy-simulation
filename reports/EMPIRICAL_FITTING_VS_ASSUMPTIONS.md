# Empirical Fitting vs Real-World Assumptions

## The Trap

It's tempting to use real F1 knowledge to parameterize the simulation. "Dirty air costs about 0.3 seconds per sector within 1.5 seconds of the car ahead." "DRS gives roughly 0.5-0.8 seconds advantage on a long straight." "Floor damage is catastrophic for ground-effect cars."

These statements are true for real Formula 1. They are **not necessarily true for F1 2025 the video game**.

## Why Game Physics Differs from Reality

F1 2025's physics engine is a game-engine approximation, not a CFD simulation or wind tunnel model. The game must:

- Run in real-time on consumer hardware (no multi-hour simulation runs)
- Feel fun and balanced (pure realism might make the game unplayable)
- Work across 20+ tracks with consistent behavior
- Approximate effects that real teams spend millions modeling

The game's dirty air model, DRS effect, tyre degradation curves, fuel consumption, and damage impacts are all **approximations with unknown parameters**. They may loosely correlate with reality, or they may diverge significantly.

## What Must Be Fitted Empirically

### Dirty Air

Real F1: dirty air (turbulent wake) reduces downforce by ~35-50% when following within 1 car length, diminishing with distance. Post-2022 ground-effect regulations reduced this significantly.

Game: Unknown. The thresholds (at what gap does dirty air start/stop), the magnitude (how many seconds per sector), and whether it varies by track or is a universal function — all must be fitted from observed telemetry data.

**Approach:** Compare sector times at different `deltaToCarInFront` values vs clean-air baseline (large gap), controlling for driver, compound, tyre age, and fuel. Start with a simple piecewise model: linear effect below a threshold, zero above.

### DRS Advantage

Real F1: DRS advantage varies from near zero (Monaco, short straights) to ~0.8 seconds (Monza, long straights). Depends on straight length, car drag levels, and wind.

Game: The advantage exists (the game implements DRS) but the exact time gain per sector per track is unknown. Could be a fixed constant, could scale with straight length, could depend on the car's setup.

**Approach:** Compare sector times when `drsAllowed=1` vs `drsAllowed=0` for the same driver under the same conditions. This is one of the cleaner knobs to fit because DRS is a binary state.

### Damage Effects

Real F1: Floor damage on a ground-effect car can cost 1-2 seconds per lap. Front wing damage depends on which element is lost. Engine damage reduces power output proportionally.

Game: Damage is reported as percentages (0-100%) for each component. The mapping from percentage to time loss is unknown and may not be linear. Floor damage might cost less than real F1 (to keep the game playable) or more (to incentivize careful driving).

**Approach:** Correlate sector times with damage percentages across sessions. The challenge is data scarcity — most sessions have zero damage.

### Tyre Temperature Sensitivity

Real F1: Each compound has an optimal operating window. Too cold = no grip. Too hot = blistering and degradation. The window is narrow (roughly 10-15 degrees C).

Game: Temperature is reported (`tyresSurfaceTemperature`, `tyresInnerTemperature`) but whether the game actually models grip as a function of temperature, or just reports decorative values, is unverified. AI cars may not be subject to temperature effects at all.

**Approach:** Fit a quadratic model (optimal window with drop-off on both sides) from sector time vs tyre temperature. If the fit shows no temperature sensitivity, this knob can be dropped.

## Open Questions (from design/CHALLENGES.md)

1. **Additive vs multiplicative model:** Is damage a fixed time penalty or a percentage of base pace? If a 10% floor damage costs 0.3s on a slow sector and 0.5s on a fast sector, the model should be multiplicative, not additive.

2. **Game settings sensitivity:** Does AI difficulty change just AI pace, or also the shape of tyre degradation and damage curves? If settings affect curve shapes, separate calibrations per settings profile are needed.

3. **Damage-tyre interaction:** Does aero damage increase tyre wear in the game? Plausible in reality (less downforce = more sliding) but unverified in the game. A cross-term in the model would be needed if this exists.

## Key Takeaway

The simulation is modeling **the game**, not reality. Every coefficient must come from observed game telemetry, not from real-world F1 data or intuition. The game's internal formulas are a black box — calibration treats them as such and fits from observations only. Assumptions from real F1 knowledge are starting hypotheses to test, not facts to encode.
