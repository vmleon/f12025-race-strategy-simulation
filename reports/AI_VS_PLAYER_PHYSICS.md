# AI Cars vs Player Car: Two Different Physics Models

F1 2025 runs **different physics models** for AI-controlled cars and the player car. Community testing and EA forum reports strongly suggest that AI cars use a simplified, pre-programmed pace reduction to simulate tyre wear, rather than the full tyre physics applied to the player.

This has a critical implication: a single unified calibration model fitted to all 20 cars describes **neither** system accurately. The calibration pipeline must maintain two separate sets of coefficients:

- **Player coefficients** — fitted from the player car's telemetry only (1 car per session). Reflects the game's full physics: tyre wear, temperature, fuel, damage.
- **AI coefficients** — fitted from the 19 AI cars' telemetry. Reflects the game's simplified AI pace model, which may show more linear and predictable degradation patterns.

When the simulation predicts a race, it must use player coefficients for the player's car and AI coefficients for the opponents. Using player-fitted tyre degradation curves for AI cars would overestimate their pace drop-off, producing overly optimistic strategy predictions.

The tradeoff: player coefficients accumulate data 19x slower (1 car vs 19). Some player-specific knobs (damage effects, weather impact) may never have enough data and will need to rely on defaults for a long time.

## See Also

- `design/CALIBRATION.md` — calibration pipeline details including dual-regime fitting
- `reports/EMPIRICAL_FITTING_VS_ASSUMPTIONS.md` — why all coefficients must come from game telemetry, not real-world physics
- `reports/COLD_START_AND_DEFAULTS.md` — cold-start defaults and the player/AI convergence asymmetry
