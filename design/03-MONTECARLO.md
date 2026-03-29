# Monte Carlo Race Strategy Simulation — Data Requirements

## Goal

Ingest F1 25 telemetry data from live sessions and store it in an Oracle relational database. The data will feed Monte Carlo simulations that predict final race results under different strategy choices (pit stop timing, tyre compound selection).

## Core Simulation Model

The simulation answers: **"Given current race state + a strategy choice, what's the probability distribution of final positions?"**

### Convergence and Iteration Count

For a 50-lap race with 20 cars at per-sector granularity, each Monte Carlo iteration simulates ~3,000 sector steps (50 laps × 3 sectors × 20 cars). Convergence depends on the scenario — a dominant leader converges fast (always P1), while a tight midfield needs many more iterations.

**For the POC:** Start with 1,000 iterations, measure wall-clock time, and check if position distributions are stable (mean and 95% CI stop shifting by more than 0.1 positions). Scale up to 10,000 only if results are noisy. Published Monte Carlo race simulations (Heilmeier et al.) use ~10,000 iterations, but per-sector granularity is 3× more expensive than per-lap.

A lap time is a function of:

```
lap_time = f(base_pace, tyre_compound, tyre_age, tyre_wear, tyre_temp, fuel_load, weather, track_temp, air_temp, track, car_damage, valid_lap)
```

### How a Single Iteration Works

Each iteration simulates the remainder of the race from the current state, sector by sector:

```
For each remaining lap:
  For each sector (0, 1, 2):
    For each car:
      sector_time = base_pace + tyre_deg + fuel_effect + damage_effect
                  + dirty_air + drs + residual_noise
      where residual_noise = gauss(0, sqrt(residual_variance))
    Resolve interactions (overtakes, position changes)
    Update car states (tyre_age++, fuel--, DRS eligibility, gaps)
```

Every sector step draws a fresh residual noise value per car. This noise represents the unexplained variance from calibration — the gap between the model's prediction and observed sector times. Stochastic events (safety car deployment, mechanical DNF, overtake success/failure) are also sampled independently each iteration.

At the end of one iteration, all remaining laps have been simulated and a complete predicted finishing order and race times exist for all cars.

### Aggregation Across Iterations

The simulation runs thousands of iterations (1,000–10,000), each producing a different race outcome because the random draws differ. Aggregating across iterations produces probability distributions:

- **Position distributions** — "Car X finishes P1 in 45% of iterations, P2 in 30%, P3 in 20%..."
- **Strategy comparison** — "Strategy A (pit lap 20, soft→hard) wins in 60% of iterations vs Strategy B (pit lap 15, soft→medium)"

### Strategy Evaluation

To compare strategies, the simulation runs a full Monte Carlo batch for each candidate strategy independently. Each candidate defines a pit stop plan (which lap to pit, which compound to switch to). The simulation injects the candidate's pit plan for the player car while AI cars follow their own heuristic pit logic.

After all candidates have been simulated, results are ranked by mean finishing position and expected championship points. Each ranked strategy includes position distribution, confidence intervals, DNF probability, and top-3 / points-finish probabilities — giving the player a complete picture of each option's risk/reward profile.

The candidate strategies themselves are provided by the caller (the Backend). Strategy generation (e.g. exploring different pit windows and compound permutations) is the Backend's responsibility; the Simulator only evaluates what it receives. This separation keeps the simulation engine focused on a single concern: running Monte Carlo iterations.

The residual variance controls how spread out the outcomes are — tight residuals (well-calibrated model) produce consistent predictions, wide residuals (poor calibration or genuinely noisy game physics) produce uncertain outcomes. The required iteration count depends on convergence — see Convergence and Iteration Count above.

**Important:** Monte Carlo does not self-calibrate. The quality of the simulation depends entirely on the quality of the model coefficients ("knobs") fitted from historical data. See `05-CALIBRATION.md` for the calibration pipeline that transforms raw telemetry into fitted coefficients for each term in the lap time function.

## Simulation Granularity

### Per-sector granularity (design choice)

We simulate at per-sector granularity rather than per-lap. This is a design choice that trades higher data volume (3× more rows) for finer resolution. Published Monte Carlo race strategy simulations (e.g. Heilmeier et al., 2020) work at per-lap granularity, but per-sector lets us model effects that are sector-specific:

- Overtakes happen in specific sectors (DRS zones, heavy braking points)
- A position change in sector 1 changes the entire dynamic for sectors 2 and 3
- DRS zones exist in specific sectors, so we can model which sectors allow overtaking

Per-sector gives us 3 data points per lap per car. For a 20-car race: 60 rows per lap — still very manageable. The tradeoff is more data to collect and store, but the resolution maps naturally to how F1 works.

### Event-driven simulation triggers

The simulation should re-run not just at sector boundaries but also when disruptive events occur:

| Trigger             | Source                         | Why                                                |
| ------------------- | ------------------------------ | -------------------------------------------------- |
| Sector completion   | LapData `sector` field changes | Baseline cadence (~every 20-30 seconds)            |
| Position change     | LapData `carPosition` changes  | Overtake changes clean air, DRS, strategy calculus |
| Weather change      | Session `weather` changes      | Triggers tyre strategy rethink                     |
| Safety car / VSC    | Event `SCAR`                   | Compresses field, free pit window                  |
| Red flag            | Event `RDFL`                   | Session stoppage, free tyre change                 |
| Pit stop (any car)  | LapData `pitStatus` changes    | Affects track position of all cars                 |
| Collision / damage  | Event `COLL`                   | May cause retirement or pace loss                  |
| Damage level change | CarDamage fields increase      | Triggers pit/retire decision + pace recalculation  |

A position change is especially important: if a car has been stuck behind a slower car and finally passes, it suddenly has clean air, better tyre deg, no more DRS for the car behind — the projected outcome for both cars changes significantly.

### Re-trigger frequency

With 20 cars completing sectors, triggers fire roughly every 1–1.5 seconds. The simulation must complete fast enough to be useful before the next trigger fires.

**For the POC:** Don't re-run on every sector completion. Instead, use a **debounced cadence**: re-run once per lap (after all cars complete a lap) plus disruptive events only (safety car, pit stop, position change, damage). Sector-level re-triggering is an optimization for later. If each run takes longer than the trigger interval, queue and batch — always run with the latest state rather than spawning concurrent simulations.

## Historical Data Requirements

The Monte Carlo simulation samples from probability distributions derived from historical data. Before running simulations in production, we need enough accumulated data to build reliable distributions. This data comes from running time trials and GPs through the ingestion pipeline.

### Required historical distributions

| Distribution                         | Built from                                                    | Minimum data needed                    |
| ------------------------------------ | ------------------------------------------------------------- | -------------------------------------- |
| Base pace per driver per track       | Sector times (valid laps, clean air)                          | Several sessions per track             |
| Tyre degradation curves per compound | Sector time vs tyre age, grouped by compound                  | Multiple stints per compound per track |
| Fuel effect on pace                  | Sector time vs fuel load                                      | Full race distances                    |
| Overtake probability per sector      | Position changes at sector boundaries vs pace delta, gap, DRS | Multiple races per track               |
| Pit stop duration distribution       | Pit lane time (in-lap sector 3 + out-lap sector 1 vs normal)  | Multiple pit stops per track           |
| Safety car / VSC probability         | Event frequency per race distance                             | Multiple races                         |
| DNF probability                      | Retirement events per car per race                            | Multiple races                         |
| Tyre temp effect on pace             | Sector time vs tyre surface/inner temp                        | Multiple stints across conditions      |
| Weather effect on pace               | Sector time deltas across weather changes                     | Sessions with weather variation        |
| Car damage effect on pace            | Sector time vs damage levels (wing, floor, engine)            | Sessions with damage incidents         |
| Damage-triggered pit/retirement prob | Pit stops and retirements correlated with damage levels       | Multiple sessions with damage events   |

### Historical context for the simulation

The simulation needs a `HistoricalContext` data structure containing pre-computed distributions for the current track + conditions. This structure is passed to the simulation engine and includes:

- Per-driver base pace distributions (mean + variance per sector)
- Tyre degradation model coefficients per compound (fitted from historical tyre age vs sector time)
- Inter-car interaction parameters (dirty air effect magnitude, DRS advantage — derived from historical gap and sector time data rather than hardcoded constants)
- Overtake probability model per sector (logistic regression or similar, fitted from historical position changes)

These values are computed offline from accumulated historical data and updated after each new session. See `05-CALIBRATION.md` for the full calibration pipeline: how each coefficient is fitted, the two-tier approach (historical baseline + optional in-session dynamic adjustment), initial default values for cold start, and validation methodology.

## Data to Capture

### 1. Per-Sector Data (one row per car per sector)

The core dataset. Captured when we detect a `sector` field transition in LapData (0→1, 1→2, 2→0). The 2025 F1 season has 20 active cars (10 teams). The game's UDP arrays hold up to 22 slots — downstream code must filter inactive car indices (check `driverStatus` or Participants data) to avoid processing empty slots.

| Field                    | Source Packet   | Source Field                      | Why                                                   |
| ------------------------ | --------------- | --------------------------------- | ----------------------------------------------------- |
| Sector time (ms)         | LapData(2)      | sector1/2TimeMSPart + MinutesPart | Core simulation input                                 |
| Lap number               | LapData(2)      | currentLapNum                     | Ordering                                              |
| Sector number            | LapData(2)      | sector (0, 1, 2)                  | Which sector just completed (recorded on transition: 0→1 records 0, 1→2 records 1, 2→0 records 2) |
| Lap time (ms)            | LapData(2)      | lastLapTimeInMS                   | Full lap time (available after sector 2→0 transition) |
| Car position             | LapData(2)      | carPosition                       | Track position at sector boundary                     |
| Pit status               | LapData(2)      | pitStatus                         | In/out laps are slower                                |
| Num pit stops            | LapData(2)      | numPitStops                       | Strategy tracking                                     |
| Lap validity             | LapData(2)      | currentLapInvalid                 | Invalidate data (not delete)                          |
| Corner cutting warnings  | LapData(2)      | cornerCuttingWarnings             | Additional invalidity signal                          |
| Penalties (seconds)      | LapData(2)      | penalties                         | Affects final result                                  |
| Driver status            | LapData(2)      | driverStatus                      | Garage, flying, in/out lap                            |
| Speed trap (km/h)        | LapData(2)      | speedTrapFastestSpeed             | Straight-line performance proxy                       |
| Gap to car ahead         | LapData(2)      | deltaToCarInFront (ms+min parts)  | Dirty air + DRS modeling per sector                   |
| Gap to race leader       | LapData(2)      | deltaToRaceLeader (ms+min parts)  | Field spread, lapped traffic                          |
| Tyre compound (actual)   | CarStatus(7)    | actualTyreCompound                | Strategy variable                                     |
| Tyre compound (visual)   | CarStatus(7)    | visualTyreCompound                | Display (C1-C5, inter, wet)                           |
| Tyre age (laps)          | CarStatus(7)    | tyresAgeLaps                      | Degradation curve input                               |
| Fuel in tank (kg)        | CarStatus(7)    | fuelInTank                        | Fuel effect on pace                                   |
| Fuel remaining (laps)    | CarStatus(7)    | fuelRemainingLaps                 | Strategy planning                                     |
| ERS deploy mode          | CarStatus(7)    | ersDeployMode                     | Overtake mode ~1s advantage                           |
| DRS allowed              | CarStatus(7)    | drsAllowed                        | DRS advantage this sector                             |
| DRS activation distance  | CarStatus(7)    | drsActivationDistance             | Proximity to DRS zone                                 |
| Tyre wear (4 wheels)     | CarDamage(10)   | tyresWear[4]                      | Degradation at sector boundary                        |
| Tyre damage (4 wheels)   | CarDamage(10)   | tyresDamage[4]                    | Performance loss                                      |
| Tyre blisters (4 wheels) | CarDamage(10)   | tyreBlisters[4]                   | Thermal degradation                                   |
| Front left wing damage   | CarDamage(10)   | frontLeftWingDamage               | Aero performance loss, pit stop trigger               |
| Front right wing damage  | CarDamage(10)   | frontRightWingDamage              | Aero performance loss, pit stop trigger               |
| Rear wing damage         | CarDamage(10)   | rearWingDamage                    | Aero + DRS loss, pit stop trigger                     |
| Floor damage             | CarDamage(10)   | floorDamage                       | Major downforce loss (ground effect)                  |
| Diffuser damage          | CarDamage(10)   | diffuserDamage                    | Rear downforce loss                                   |
| Sidepod damage           | CarDamage(10)   | sidepodDamage                     | Cooling + aero loss                                   |
| Gearbox damage           | CarDamage(10)   | gearBoxDamage                     | Reliability risk, potential retirement                |
| Engine damage            | CarDamage(10)   | engineDamage                      | Pace loss, reliability risk, potential retirement     |
| Tyre surface temp (4)    | CarTelemetry(6) | tyresSurfaceTemperature[4]        | Grip level, degradation rate                          |
| Tyre inner temp (4)      | CarTelemetry(6) | tyresInnerTemperature[4]          | Core tyre temp, optimal window tracking               |
| Brake temp (4 wheels)    | CarTelemetry(6) | brakesTemperature[4]              | Affects tyre temp, brake wear indicator               |
| Engine temperature       | CarTelemetry(6) | engineTemperature                 | Reliability risk, overheating signal                  |
| Weather                  | Session(1)      | weather                           | Major pace factor                                     |
| Track temperature        | Session(1)      | trackTemperature                  | Tyre behavior                                         |
| Air temperature          | Session(1)      | airTemperature                    | Tyre behavior                                         |
| Safety car status        | Session(1)      | safetyCarStatus                   | Bunches field, free pit stops                         |

### 2. Session Events (one row per event)

Discrete events that affect race outcome. Captured from Event(3) packets.

| Event Code | Meaning                      | Simulation Use                     |
| ---------- | ---------------------------- | ---------------------------------- |
| SSTA       | Session started              | Session boundaries                 |
| SEND       | Session ended                | Session boundaries                 |
| SCAR       | Safety car deployed/returned | Compresses field, free pit window  |
| RDFL       | Red flag                     | Session stoppage, free tyre change |
| RTMT       | Retirement                   | DNF probability modeling           |
| PENA       | Penalty issued               | Time penalties affect result       |
| FTLP       | Fastest lap                  | Benchmark pace                     |
| CHQF       | Chequered flag               | Race end                           |

### 3. Session Metadata (one row per session)

Captured from Session(1) packet, written once at session start and updated if values change.

| Field                   | Source Field            | Why                                 |
| ----------------------- | ----------------------- | ----------------------------------- |
| Session UID             | header.sessionUID       | Primary key / grouping              |
| Track ID                | trackId                 | Different tracks = different models |
| Track length (m)        | trackLength             | For speed calculations              |
| Session type            | sessionType             | Practice/quali/race                 |
| Total laps              | totalLaps               | Race length                         |
| Formula                 | formula                 | F1/F2/etc                           |
| Sector 2 start distance | sector2LapDistanceStart | Sector boundary validation          |
| Sector 3 start distance | sector3LapDistanceStart | Sector boundary validation          |
| AI difficulty           | aiDifficulty            | Relevant if racing AI               |
| Safety car setting      | safetyCar               | Frequency of SC events              |
| Car damage setting      | carDamageSetting        | 0=Off, 1=Reduced, 2=Standard, 3=Sim |
| Car damage rate         | carDamageRate           | 0=Reduced, 1=Standard, 2=Simulation |
| Low fuel mode           | lowFuelMode             | 0=Easy, 1=Hard                      |

### 4. Participants (one row per car per session)

Captured from Participants(4) packet. Identifies who is driving what.

| Field         | Source Field | Why                      |
| ------------- | ------------ | ------------------------ |
| Car index     | array index  | Links to per-lap data    |
| Driver name   | name         | Identification           |
| Team ID       | teamId       | Car performance grouping |
| AI controlled | aiControlled | Human vs AI flag         |
| Race number   | raceNumber   | Display                  |
| Nationality   | nationality  | Display                  |

### 5. Final Classification (one row per car per session)

Captured from FinalClassification(8) packet, sent once at end of session. Contains the game-official final results and the complete strategy executed by each car. This data serves two purposes:

1. **Simulation validation** — compare predicted final positions and race times against actual outcomes to measure simulation accuracy.
2. **Ground truth for strategy** — the executed strategy (number of pit stops, tyre compounds per stint, stint lengths) is the baseline that the simulation's "what if" alternatives are measured against.

| Field                  | Source Field            | Why                                            |
| ---------------------- | ----------------------- | ---------------------------------------------- |
| Finishing position     | position                | Compare against simulation-predicted position  |
| Grid position          | gridPosition            | Starting condition for simulation replay       |
| Laps completed         | numLaps                 | DNF detection, partial race handling           |
| Points scored          | points                  | Championship impact of strategy choices        |
| Num pit stops          | numPitStops             | Actual strategy vs simulated alternatives      |
| Result status          | resultStatus            | Finished / DNF / DSQ classification            |
| Result reason          | resultReason            | Why a car retired (mechanical, damage, etc.)   |
| Best lap time (ms)     | bestLapTimeInMS         | Peak pace reference                            |
| Total race time (s)    | totalRaceTime           | Predicted vs actual total time                 |
| Penalties time (s)     | penaltiesTime           | Penalty impact on final result                 |
| Num tyre stints        | numTyreStints           | Strategy complexity                            |
| Tyre compounds (stint) | tyreStintsActual/Visual | Which compounds were used and in what order    |
| Stint end laps         | tyreStintsEndLaps       | When each pit stop happened (stint boundaries) |

### Simulation Validation

After each race, the simulation's predictions can be scored against FinalClassification data:

- **Position accuracy** — mean absolute error between predicted and actual finishing positions across all cars.
- **Race time accuracy** — predicted vs actual `totalRaceTime` for cars that finished (resultStatus=3).
- **Strategy comparison** — did the simulation correctly identify the optimal strategy? Compare its recommended pit stop count and compound sequence against what the winner and podium finishers actually used.
- **DNF calibration** — did the simulation's DNF probability model predict roughly the right number of retirements?

This feedback loop is what makes the simulation improvable over time: each race produces both new training data (sector snapshots) and a validation set (final classification).

### 6. Tyre Sets Inventory (one snapshot per car, updated on pit stops)

Captured from TyreSets(12) packet. Contains all available tyre sets (13 dry + 7 wet) for a specific car. The simulation needs this to know which strategies are actually feasible — you can't plan a 2-stop on fresh softs if all soft sets were used in practice/qualifying.

Captured once at session start and re-captured whenever `pitStatus` changes for that car (a pit stop may change which set is fitted and which sets remain available).

| Field                  | Source Field       | Why                                       |
| ---------------------- | ------------------ | ----------------------------------------- |
| Tyre compound (actual) | actualTyreCompound | Which compound this set is                |
| Tyre compound (visual) | visualTyreCompound | Display (C1-C5, inter, wet)               |
| Wear (%)               | wear               | How used this set is                      |
| Available              | available          | Whether this set can still be used        |
| Life span (laps)       | lifeSpan           | Remaining laps in this set                |
| Usable life (laps)     | usableLife         | Max recommended laps for this compound    |
| Lap delta time (ms)    | lapDeltaTime       | Performance delta vs currently fitted set |
| Fitted                 | fitted             | Whether this set is currently on the car  |
| Fitted index           | fittedIdx          | Index of the currently fitted set         |

**Simulation use:** When evaluating strategy alternatives, the simulation checks tyre set availability to constrain the search space. A strategy that requires 3 stints on softs is only valid if 3 soft sets are available with enough usable life. The `lapDeltaTime` field helps estimate the pace difference between switching to a used set vs. a fresh one.

## Inter-Car Interactions

The simulation cannot treat each car independently. Key interactions that affect lap times:

### Dirty Air

Following a car closely reduces downforce and increases tyre degradation. The exact thresholds and magnitude of this effect in the game are unknown — the game's dirty air model is a game-engine approximation, not a physics simulation, and may not match real F1 behavior. Rather than hardcoding a fixed effect or assuming real-world aero knowledge, the simulation derives dirty air impact entirely from historical data: comparing sector times when a car has a small `deltaToCarInFront` vs clean-air sector times for the same driver/compound/tyre age. Per-sector resolution lets the simulation see "car X was stuck in dirty air through sectors 1 and 2, then passed in sector 3" — much richer than a single per-lap average. See `09-CHALLENGES.md` (Challenge 7) for open questions about fitting the dirty air curve.

### DRS Effect

Within 1 second of the car ahead at the detection point, DRS activates on the next straight. The time advantage varies significantly by track (near zero at Monaco, up to ~0.8s at Monza). We capture `drsAllowed` per car per sector. Since DRS zones exist in specific sectors, per-sector data lets us model exactly which sectors see DRS-assisted overtakes. Like dirty air, the actual DRS advantage is derived from historical sector time deltas rather than a hardcoded constant.

### Traffic / Slower Cars

A faster car stuck behind a slower car loses time until it overtakes. The simulation needs to model overtake probability as a function of:

- Pace delta between the two cars
- Gap size (smaller = more DRS benefit but harder to pass)
- Track overtaking difficulty (Monaco vs Monza)
- Tyre compound difference (fresh vs worn)

Factors like tyre age, fuel load, and car damage affect overtake probability **indirectly** — they change the pace of each car, which changes the pace delta, which is the primary input to the overtake model. This is a deliberate design choice: rather than making tyre age a direct input to the overtake probability function, the simulation lets the pace model translate all performance factors into a single pace delta that drives overtake likelihood. This avoids double-counting effects and keeps the overtake model simple (fewer coefficients to fit from limited position-change data). See `05-CALIBRATION.md` §9 for the logistic regression fitting approach.

Since we capture all active cars' lap data, the simulation can identify when a faster car is stuck behind a slower one (same lap time despite better pace on clear track) and model this correctly.

### Defending

A car defending position uses more fuel, takes sub-optimal lines, and wears tyres more. This is partially captured implicitly: a car that's defending will show higher tyre wear and slower sector times in the data. The gap-to-car-behind can be derived from the car behind's `deltaToCarInFront`. However, for prediction purposes, the simulation must decide whether defending is modeled explicitly (as an input variable) or left as noise in the residuals. See `05-CALIBRATION.md` (Defending: Not Modeled Explicitly) for the tradeoff analysis.

### Car Damage

Car damage from collisions, kerb strikes, or component wear directly affects pace and can trigger strategic decisions (pit for repairs, retire, or continue with reduced performance). We capture damage levels for all aero surfaces (front wings, rear wing, floor, diffuser, sidepod) and mechanical components (engine, gearbox).

**Pace impact:** Aero damage (especially floor and front wing) reduces downforce, increasing sector times primarily in high-speed corners. Engine damage reduces straight-line speed. The simulation derives damage-to-pace relationships from historical data: comparing sector times at different damage levels for the same driver/compound/tyre age. Floor damage is particularly significant with 2022+ ground-effect cars — even small floor damage can cost substantial lap time.

**Strategy decisions the simulation must model:**

- **Pit for repairs** — If damage exceeds a threshold, pitting for a new front wing (or other replaceable parts) may be faster than continuing with degraded pace. The simulation compares projected race time with damage vs. time lost pitting for repairs.
- **Retirement probability** — Engine and gearbox damage above certain levels risk mechanical failure. The simulation models retirement probability as a function of damage level and remaining race distance — a car with 60% engine damage on lap 5 is far more likely to retire than one with 20% on the final lap.
- **Compound effect with tyres** — Aero damage may increase tyre degradation (less downforce = more sliding = more wear). Whether this interaction exists in the game is unverified — it is plausible in real F1 but the game may treat damage and tyre wear independently. See `09-CHALLENGES.md` (Challenge 8) for how to test this.

### Penalties

Race penalties affect both strategy decisions and final results. The simulation models three penalty types from the game's PENA events:

| Type | Effect | Serving |
|------|--------|---------|
| **Time penalty** | Seconds added to final race time | Automatic — applied after the chequered flag |
| **Drive-through** | Must transit pit lane at speed limit | Manual — driver chooses when to serve |
| **Stop-go** | Must transit pit lane + stop for N seconds | Manual — driver chooses when to serve |

**State separation:** The simulation tracks penalties in two distinct buckets per car:

- `penalty_time_ms` — accumulated time penalties. Deterministic, applied once at end of iteration to `total_time_ms`. Does not affect position during the race.
- `pending_penalties` — drive-through and stop-go penalties awaiting service. Each has a `laps_to_serve` grace window (default 3 laps). These affect strategy because the driver must pit to serve them.

**Serving logic:** Pending penalties are evaluated at sector 0 (start of each lap):

- **AI cars** serve immediately on the next available lap — no strategic delay
- **Human players** serve on the final lap of their window (maximizing time on track before incurring the pit lane time loss)
- **Disqualification:** if `laps_to_serve` reaches 0 without serving, the car is retired (marked DNF)

**Cost model:**

- Drive-through cost = `pit_lane_transit_time` (calibrated from pit stop duration fitting, ~20s default)
- Stop-go cost = `pit_lane_transit_time` + `penalty.seconds × 1000` (typically 5–10s additional stationary time)

**Design rationale:** Time penalties are separated from serve-able penalties because they have fundamentally different simulation mechanics. Time penalties are deterministic end-of-race adjustments that don't change the race trajectory. Serve-able penalties inject a mandatory pit stop that alters race timing and strategy — the simulation must model when (and whether) the car serves, and the pace consequences of the detour.

## Recovery Data (in-memory only)

### 7. SessionHistory (recovery buffer, not persisted)

SessionHistory(11) packets provide game-validated per-lap and per-sector times for a specific car. While redundant with our primary capture from LapData sector transitions, SessionHistory serves as a **recovery source** when UDP packet loss causes a missed sector transition (see Capture Strategy below).

SessionHistory is maintained in-memory as a **sliding window of the last N laps per car** (e.g. N=5). It is NOT persisted to the database — it exists only to fill gaps when a LapData sector transition packet is lost. Once a sector row is successfully written (from either primary or recovery path), the corresponding SessionHistory data can be discarded.

## Data NOT Captured

High-frequency per-frame data is unnecessary for strategy simulation:

- **Motion / MotionEx** — Car world position every frame. Not needed at sector granularity.
- **CarTelemetry(6) per frame** — Throttle/brake/steering at high frequency. Per-frame CarTelemetry is not persisted, but tyre surface/inner temps, brake temps, and engine temp are snapshotted at sector boundaries (see per-sector data). The rest (speed, throttle, brake, gear, steering) is not stored.
- **CarSetups** — Wing angles, suspension. Doesn't change mid-race.
- **TyreSets per frame** — Captured once at session start and on pit stops (see Tyre Sets Inventory), not every packet.
- **LapPositions** — Position chart data, reconstructable from per-sector position.
- **TimeTrial** — Time trial specific, not race simulation.

## Capture Strategy

We do NOT store every incoming packet. Instead:

1. **Maintain in-memory "latest state"** per car — update it with every Session, CarStatus, CarDamage, CarTelemetry packet.
2. **On sector change** (detect `sector` field transition in LapData for each car) — snapshot the current state into one per-sector row.
3. **On SessionHistory packets** — update the in-memory sliding window buffer (last N laps per car). Do not persist.
4. **On Event packets** — write event rows immediately.
5. **On FinalClassification packet** — write one row per car with final results and strategy. Sent once at session end.
6. **On Session packet (first seen)** — write session metadata + participants.
7. **On TyreSets packet** — snapshot tyre set inventory at session start and when a pit stop is detected (pitStatus change in LapData).

At the default 20Hz send rate, the game emits roughly 80–100 packets/second across all packet types. This strategy reduces that to ~60 persisted rows per lap (3 sectors × 20 active cars).

### Handling missed sector transitions (UDP loss recovery)

UDP does not guarantee delivery. If a LapData packet containing a sector transition is lost, the primary snapshot is missed. Recovery strategy:

1. **Primary detection:** On each LapData packet, compare current `sector` value against the last-seen `sector` for that car. A change triggers a snapshot.
2. **Gap detection:** If `sector` jumps by more than one step (e.g. 0→2, meaning sector 1 transition was missed), we know a packet was lost.
3. **Recovery from LapData:** The next LapData packet after the missed transition still contains the completed sector time fields (`sector1TimeInMS`, `sector2TimeInMS`). Use these cumulative times to reconstruct the missed sector row. This is the fastest recovery path since LapData arrives frequently.
4. **Recovery from SessionHistory (fallback):** If LapData recovery is insufficient (e.g. multiple consecutive packets lost), use the SessionHistory sliding window buffer which contains game-validated sector times. This is slower but more complete.
5. **Flag gaps:** If neither recovery source can fill the gap, write the sector row with a `recovered=false` flag so the simulation knows to exclude or weight it accordingly.

**Implementation notes:**

- **SessionHistory sliding window size:** N=5 laps per car. Memory cost is bounded (22 cars × 5 laps × sector data). SessionHistory is sent every 2 ticks — frequent enough to have recovery data available before the next sector transition
- **Multiple consecutive lost packets:** If 3+ LapData packets are lost in a row, both primary detection and LapData recovery may fail. SessionHistory becomes the only option. At the default 20Hz send rate, 3+ consecutive losses within the ~1s between sector transitions is rare but possible under heavy system load
- **Race start (lap 1):** Excluded from calibration but must be handled correctly for position tracking. Lap 1 has standing start, cold tyres, and frequent collisions — sector times are not meaningful for calibration
- **For the POC:** Log all recovery events with context (which car, which sector, which recovery path). Analyze after a few sessions to determine actual UDP loss rate and whether N=5 is sufficient

## Packet Frequency

The game's UDP send rate is configurable: **10Hz, 20Hz (default), 30Hz, or 60Hz**. The rates below are relative to the send rate. "Every 2 ticks" means the packet is sent at half the configured rate (e.g. 10 packets/sec at 20Hz).

| Packet                                                        | Frequency                                  |
| ------------------------------------------------------------- | ------------------------------------------ |
| Motion, MotionEx, CarTelemetry, CarStatus, LapData, CarDamage | Every 2 ticks                              |
| SessionHistory, TyreSets                                      | Every 2 ticks                              |
| Session, CarSetups, TimeTrial, LapPositions                   | Every ~10 ticks                            |
| Event                                                         | Sporadic (on occurrence)                   |
| Participants                                                  | Infrequent (session start, driver changes) |

At 20Hz default, this produces roughly 80–100 UDP packets/second total. At 60Hz, roughly 240–300. Higher rates reduce the chance of missing sector transitions but increase network and CPU load.
