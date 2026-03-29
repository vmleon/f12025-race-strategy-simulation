# Monte Carlo Is Not Enough

The initial assumption was that a Monte Carlo simulation engine alone could predict race outcomes. In practice, Monte Carlo is **dumb sampling** — it draws from whatever distributions and coefficients you give it. It does not learn, adjust, or self-correct.

A working system requires three distinct pipelines:

1. **Ingestion** — Capture real-time UDP telemetry from the F1 2025 game and persist it to a database. The game emits ~80-100 packets/second across 16 packet types. Only a fraction needs to be stored: one snapshot per car per sector completion (~60 rows per lap for 20 cars), plus discrete events and session metadata.

2. **Calibration** — Fit model coefficients ("knobs") from the accumulated historical data. The lap time model has ~10 knobs (tyre degradation, fuel effect, dirty air, DRS advantage, damage effects, weather, etc.), each requiring enough observed data points to produce reliable fitted values. Without calibration, the simulation would rely on hardcoded guesses with wide, uninformed distributions.

3. **Simulation** — The Monte Carlo engine itself. It takes the fitted coefficients from calibration and runs thousands of iterations. Each iteration simulates all remaining laps sector-by-sector for all 20 cars, drawing fresh residual noise (from the calibration regression residual variance) at every sector step. Stochastic events (safety cars, DNFs, overtake outcomes) are also sampled per iteration. Aggregating across iterations produces probability distributions of finishing positions and race times under different strategy choices.

```
[Game UDP] → [Ingestion] → [Database] → [Calibration] → [Fitted Coefficients] → [Monte Carlo]
```

The quality of predictions depends entirely on the quality of calibration, which depends entirely on the volume and quality of ingested data. Monte Carlo without calibration is just random number generation with extra steps.

## See Also

- `design/MONTECARLO.md` — simulation mechanics, convergence criteria, and data capture specification
- `design/CALIBRATION.md` — calibration pipeline details and fitting methodology
