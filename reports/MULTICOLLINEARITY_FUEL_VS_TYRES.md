# Multicollinearity: Separating Fuel Effect from Tyre Degradation

## The Problem

The lap time model includes both fuel effect and tyre degradation as separate terms:

```
sector_time = base_pace + tyre_degradation(compound, tyre_age) + fuel_effect(fuel_load) + ...
```

Within a single stint, `fuelInTank` and `tyresAgeLaps` are almost **perfectly linearly correlated**. Both change by a near-constant amount per lap — fuel decreases at a steady burn rate, tyre age increases by exactly 1 lap. A multivariate regression on within-stint data **cannot reliably separate** which portion of the time loss comes from heavier tyres versus lighter fuel.

This is a textbook multicollinearity problem. The regression will still converge, but the individual coefficients for fuel and tyre age will be unstable — small changes in the data cause large swings in the estimated values, even though their combined effect is well-estimated.

## Why This Matters

If fuel and tyre effects can't be separated, every tyre degradation coefficient is contaminated with fuel effects. The simulation uses these coefficients to evaluate strategy choices: "Should I pit now on fresh tyres or push 5 more laps?" If the tyre degradation curve includes hidden fuel effects, the answer will be wrong — the model thinks old tyres are slower than they actually are (because it attributes fuel-related slowness to tyre age).

## Solutions

### 1. Cross-stint comparison

Find laps where tyre age matches but fuel load differs. For example, lap 5 of stint 1 (heavy fuel, 5-lap-old tyres) vs lap 5 of stint 2 (lighter fuel, also 5-lap-old tyres). The tyre age is the same, so any sector time difference is attributable to fuel load.

This requires enough sessions with varied pit strategies so that the fuel/tyre-age correlation breaks across stints.

### 2. Known fuel burn rate subtraction

The game's fuel consumption rate can be measured precisely from `fuelInTank` deltas between consecutive laps. If the burn rate is near-constant (which F1 2025 appears to enforce), subtract the estimated fuel effect analytically before fitting tyre degradation.

This is a two-stage approach:
1. Measure fuel burn rate from the data (delta of `fuelInTank` per lap)
2. Compute expected fuel time penalty per lap using a known or estimated fuel coefficient
3. Subtract this from sector times
4. Fit tyre degradation on the residual

### 3. Pit stop resets

A pit stop resets tyre age to 0 but fuel stays at whatever level it was (no refueling in F1 2025). Comparing pre-pit and post-pit sector times for the same car at the same track isolates the tyre effect — the fuel load is continuous across the pit stop, but tyre age jumps from old to zero.

## Practical Approach for the PoC

Start with approach #2 (fuel burn rate subtraction) because it requires the least data and can be applied within individual stints. Use approach #1 (cross-stint comparison) as validation once enough sessions accumulate.

## Key Takeaway

When two variables are structurally correlated in the data (as fuel and tyre age are within a stint), no amount of statistical technique can separate them from that data alone. You need variation in one while the other is held constant — and in this case, that variation only exists across stints, not within them.
