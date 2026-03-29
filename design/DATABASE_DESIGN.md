# Database Schema Design — F1 Telemetry for Monte Carlo Simulation

## Overview

Nine tables: six mapping 1:1 to the data categories defined in `MONTECARLO.md` (session metadata, participants, per-sector snapshots, events, tyre sets inventory, final classifications), a `calibration_coefficients` table for fitted model values from `CALIBRATION.md`, and `drivers` + `driver_sessions` for driver identity management. Stints are derivable from sector data. Weather transitions are visible in per-sector snapshots.

## Key Design Decisions

### 1. Natural composite PK on `sector_snapshots`, no surrogate key

`(session_uid, car_index, lap_number, sector_number)` is a natural unique identifier — there is exactly one sector completion per car per sector per lap within a session. The game does not reset `currentLapNum` on red flags within the same session_uid.

- **No extra unique constraint needed** — a surrogate key would still require a unique constraint on `(session_uid, car_index, lap_number, sector_number)` to prevent duplicates. The natural PK serves both purposes.
- **Idempotent writes** — if a recovered sector row arrives after the primary one, the PK constraint catches the duplicate.

### 2. Denormalize weather/track_temp/air_temp INTO sector_snapshots

This is intentional, not accidental denormalization. Weather changes mid-race. Track temperature changes every few minutes. If these lived only in `sessions`, you'd lose the temporal dimension — you wouldn't know that lap 15 sector 2 was driven in rain while lap 14 sector 3 was dry. Each sector snapshot captures the conditions **at that moment**, which is exactly what the simulation needs for the `lap_time = f(..., weather, track_temp, air_temp, ...)` function.

### 3. Tyre data as flat columns, NOT a child table

12 columns (4 wheels x 3 metrics: wear, damage, blisters) inline in `sector_snapshots`. A normalized `tyre_wheel_data` child table would mean 4 child rows per sector snapshot, turning every analytical query into a pivot/join nightmare. The data is always exactly 4 values, always read together, never sparse. Flat columns are correct here.

### 4. Events: surrogate PK with a sequence

Unlike sector_snapshots, events don't have a clean natural key. Two cars can retire on the same frame (collision). A penalty and a fastest lap can coincide. `(session_uid, frame_identifier, event_code)` would lose data in edge cases. A sequence is the pragmatic choice for a low-volume table (~10-50 events per race).

### 5. Tyre stints as flat columns in `final_classifications`

Same reasoning as tyre wheel data in sector_snapshots. The game sends exactly 8 stint slots per car — always the same shape, always read together. A child `final_classification_stints` table would mean up to 8 rows per car per session for a table that has at most 22 rows per session. The joins add complexity for no benefit. `num_tyre_stints` tells you how many of the stint columns are populated; the rest are NULL.

### 6. `recovered` flag as data quality signal

The simulation weights data by confidence. Primary captures (sector transition detected in real-time) are highest confidence. LapData-recovered data is good. SessionHistory-recovered is acceptable. Unrecoverable gaps are flagged so the simulation can exclude them. This avoids polluting distributions with unreliable data without silently dropping rows.

### 7. Car damage and tyre temperatures as flat columns

Same reasoning as tyre wear (decision #3). 8 damage columns (one per car component), 8 tyre temperature columns (4 wheels × surface + inner), 4 brake temperature columns, and 1 engine temperature column are always exactly the same shape, always read together, never sparse. These feed the damage-effect and tyre-temperature calibration knobs directly.

### 8. Surrogate PK on `calibration_coefficients`

Multiple fitting methods can produce coefficients for the same knob on the same track. There is no natural unique key — `(track_id, knob_name, calibration_regime, method_name)` could still have multiple runs with different `trained_at` timestamps. A sequence-based PK is the pragmatic choice, same reasoning as events (decision #4).

### 9. No `track_id` denormalized into `sector_snapshots`

Tempting — the most common analytical pattern is "all data for track X." But `sessions` will have hundreds of rows, not millions. The join `sector_snapshots JOIN sessions USING (session_uid) WHERE track_id = ?` hits the sessions PK, resolves to a set of session_uids, then scans sector_snapshots by PK prefix. Oracle's optimizer handles this trivially. The denormalization would save one cheap join at the cost of an extra column on every row of the highest-volume table, plus an update path if you ever correct a track_id.

### 10. `outlier` flag on `sector_snapshots`

The calibration pipeline applies hard filters (invalid laps, pit laps, safety car, lap 1) but these miss **performance anomalies** — lock-ups, spins, and traffic incidents that produce valid but statistically unusual sector times. An `outlier NUMBER(1) DEFAULT 0` column marks these rows so calibration excludes them (`AND outlier = 0`) while driver feedback views retain them with the flag highlighted.

The flag is recomputed each calibration run via per-driver IQR analysis (see `CALIBRATION.md` — Outlier Detection). It is not set during ingestion — the ingestion pipeline writes `outlier = 0` (the default) and calibration updates it in batch.

### 11. Separate `driver_ratings` table for skill ratings

A per-driver skill rating (0–100) used as a cold-start prior for outlier detection before enough data exists to compute IQR. This is a separate table rather than a column on `participants` because ratings are a cross-session concept — the same driver appears across many sessions, and the rating is set once globally (with optional per-track overrides). Keeping it separate avoids modifying the high-volume participant data path and keeps the rating lifecycle independent of session ingestion.

### 12. Separate `drivers` table with session junction

Drivers are first-class entities decoupled from session participation. A `drivers` table stores identity (name, email, created_at), and `driver_sessions` is a junction table linking drivers to specific session participants via `(driver_id, session_uid)` with a compound PK.

- **Rationale:** Participants are per-session records (car index, team, AI flag). Driver identity is a cross-session concept — the same person races across multiple sessions and tracks. Normalizing driver identity enables cross-session analytics (win rates, consistency trends) without scanning participants.
- **Unique name constraint:** Enforced at DB level (`uq_drivers_name`). The API catches duplicate key violations.
- **FK to participants:** `driver_sessions` references `participants(session_uid, car_index)` — linking to the specific car the driver controlled in that session.
- **No cascade delete:** Deleting a driver does not cascade to `driver_sessions` to avoid silently orphaning session records. Explicit cleanup is required.

## DDL

```sql
-- =============================================================
-- 1. SESSIONS — one row per game session
-- =============================================================
CREATE TABLE sessions (
    session_uid          NUMBER(20)    NOT NULL,
    track_id             NUMBER(3)     NOT NULL,
    track_length_m       NUMBER(6,1),
    session_type         NUMBER(2)     NOT NULL,
    total_laps           NUMBER(3),
    formula              NUMBER(1),
    sector2_start_dist   NUMBER(10,2),
    sector3_start_dist   NUMBER(10,2),
    ai_difficulty        NUMBER(3),
    safety_car_setting   NUMBER(1),
    car_damage_setting   NUMBER(1),    -- 0=Off, 1=Reduced, 2=Standard, 3=Simulation
    car_damage_rate      NUMBER(1),    -- 0=Reduced, 1=Standard, 2=Simulation
    low_fuel_mode        NUMBER(1),    -- 0=Easy, 1=Hard
    created_at           TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_sessions PRIMARY KEY (session_uid)
);

-- =============================================================
-- 2. PARTICIPANTS — one row per car per session
-- =============================================================
CREATE TABLE participants (
    session_uid    NUMBER(20)    NOT NULL,
    car_index      NUMBER(2)     NOT NULL,
    driver_name    VARCHAR2(48),
    team_id        NUMBER(3),
    race_number    NUMBER(3),
    nationality    NUMBER(3),
    ai_controlled  NUMBER(1),

    CONSTRAINT pk_participants PRIMARY KEY (session_uid, car_index),
    CONSTRAINT fk_part_session FOREIGN KEY (session_uid)
        REFERENCES sessions (session_uid)
);

-- =============================================================
-- 3. SECTOR_SNAPSHOTS — one row per car per sector completion
--    This is the core high-volume table (~60 rows/lap).
-- =============================================================
CREATE TABLE sector_snapshots (
    -- Identity (natural composite PK)
    session_uid          NUMBER(20)    NOT NULL,
    car_index            NUMBER(2)     NOT NULL,
    lap_number           NUMBER(3)     NOT NULL,
    sector_number        NUMBER(1)     NOT NULL,  -- Records the sector just COMPLETED: 0 = sector 1 completed (LapData.sector transitions 0→1), 1 = sector 2 completed (1→2), 2 = sector 3 completed / lap finished (2→0)

    -- Timing
    sector_time_ms       NUMBER(10),
    lap_time_ms          NUMBER(10),              -- populated on sector 2->0 only

    -- Position & gaps
    car_position         NUMBER(2),
    gap_to_car_ahead_ms  NUMBER(10),
    gap_to_leader_ms     NUMBER(10),

    -- Pit & penalties
    pit_status           NUMBER(1),               -- 0=none, 1=pitting, 2=in pit
    num_pit_stops        NUMBER(2),
    penalties_sec        NUMBER(3),

    -- Validity
    lap_invalid          NUMBER(1),
    corner_cutting_warnings NUMBER(2),
    driver_status        NUMBER(1),

    -- Speed
    speed_trap_kmh       NUMBER(5,1),

    -- Tyres (compound & age)
    tyre_compound_actual NUMBER(2),
    tyre_compound_visual NUMBER(2),
    tyre_age_laps        NUMBER(3),

    -- Tyre wear (4 wheels: RL, RR, FL, FR)
    tyre_wear_rl         NUMBER(5,1),
    tyre_wear_rr         NUMBER(5,1),
    tyre_wear_fl         NUMBER(5,1),
    tyre_wear_fr         NUMBER(5,1),

    -- Tyre damage (4 wheels)
    tyre_damage_rl       NUMBER(3),
    tyre_damage_rr       NUMBER(3),
    tyre_damage_fl       NUMBER(3),
    tyre_damage_fr       NUMBER(3),

    -- Tyre blisters (4 wheels)
    tyre_blisters_rl     NUMBER(3),
    tyre_blisters_rr     NUMBER(3),
    tyre_blisters_fl     NUMBER(3),
    tyre_blisters_fr     NUMBER(3),

    -- Car damage (percentage, from CarDamage packet)
    front_wing_damage_l    NUMBER(3),
    front_wing_damage_r    NUMBER(3),
    rear_wing_damage       NUMBER(3),
    floor_damage           NUMBER(3),
    diffuser_damage        NUMBER(3),
    sidepod_damage         NUMBER(3),
    engine_damage          NUMBER(3),
    gearbox_damage         NUMBER(3),

    -- Tyre temperatures (celsius, from CarTelemetry packet)
    tyre_surface_temp_rl   NUMBER(3),
    tyre_surface_temp_rr   NUMBER(3),
    tyre_surface_temp_fl   NUMBER(3),
    tyre_surface_temp_fr   NUMBER(3),
    tyre_inner_temp_rl     NUMBER(3),
    tyre_inner_temp_rr     NUMBER(3),
    tyre_inner_temp_fl     NUMBER(3),
    tyre_inner_temp_fr     NUMBER(3),

    -- Brake temperatures (celsius, from CarTelemetry packet)
    brake_temp_rl          NUMBER(5),
    brake_temp_rr          NUMBER(5),
    brake_temp_fl          NUMBER(5),
    brake_temp_fr          NUMBER(5),

    -- Engine temperature (celsius, from CarTelemetry packet)
    engine_temp            NUMBER(5),

    -- Fuel & ERS
    fuel_in_tank_kg      NUMBER(5,2),
    fuel_remaining_laps  NUMBER(5,2),
    ers_deploy_mode      NUMBER(1),

    -- DRS
    drs_allowed          NUMBER(1),
    drs_activation_dist  NUMBER(6,1),

    -- Conditions (snapshot from Session packet at this moment)
    weather              NUMBER(1),
    track_temp           NUMBER(3),
    air_temp             NUMBER(3),
    safety_car_status    NUMBER(1),

    -- Session type (denormalized from sessions for calibration filtering)
    session_type         NUMBER(2)     NOT NULL,

    -- Data quality
    recovered            NUMBER(1)     DEFAULT 0 NOT NULL,
        -- 0 = primary capture (sector transition detected)
        -- 1 = recovered from LapData cumulative times
        -- 2 = recovered from SessionHistory buffer
        -- 3 = gap flagged, incomplete data

    frame_identifier     NUMBER(10),
    created_at           TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_sector_snapshots
        PRIMARY KEY (session_uid, car_index, lap_number, sector_number),
    CONSTRAINT fk_sector_session
        FOREIGN KEY (session_uid) REFERENCES sessions (session_uid),
    CONSTRAINT fk_sector_participant
        FOREIGN KEY (session_uid, car_index) REFERENCES participants (session_uid, car_index),
    CONSTRAINT chk_sector_number
        CHECK (sector_number IN (0, 1, 2)),
    CONSTRAINT chk_recovered
        CHECK (recovered IN (0, 1, 2, 3))
);

-- =============================================================
-- 4. SESSION_EVENTS — discrete race events
-- =============================================================
CREATE SEQUENCE seq_session_events START WITH 1 INCREMENT BY 1;

CREATE TABLE session_events (
    event_id         NUMBER(12)    NOT NULL,
    session_uid      NUMBER(20)    NOT NULL,
    frame_identifier NUMBER(10)    NOT NULL,
    event_code       VARCHAR2(4)   NOT NULL,  -- SSTA, SEND, SCAR, RTMT, PENA, FTLP, CHQF, COLL, RDFL
    car_index        NUMBER(2),               -- NULL for session-wide events (SSTA, SCAR, RDFL, CHQF)
    penalty_seconds  NUMBER(3),               -- for PENA events
    other_car_index  NUMBER(2),               -- for COLL/PENA events (other car involved)
    lap_number       NUMBER(3),               -- lap where the event occurred
    created_at       TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_session_events PRIMARY KEY (event_id),
    CONSTRAINT fk_event_session
        FOREIGN KEY (session_uid) REFERENCES sessions (session_uid)
);

-- =============================================================
-- 5. TYRE_SETS — one row per tyre set per car per session
--    Captured from TyreSets(12) at session start and on pit stops.
-- =============================================================
CREATE TABLE tyre_sets (
    session_uid          NUMBER(20)    NOT NULL,
    car_index            NUMBER(2)     NOT NULL,
    set_index            NUMBER(2)     NOT NULL,   -- 0-19 (13 dry + 7 wet sets)
    tyre_compound_actual NUMBER(2)     NOT NULL,
    tyre_compound_visual NUMBER(2)     NOT NULL,
    wear                 NUMBER(5,1),              -- How used this set is (percentage)
    available            NUMBER(1),                -- Whether this set can still be used
    life_span            NUMBER(3),                -- Remaining laps in this set
    usable_life          NUMBER(3),                -- Max recommended laps for this compound
    lap_delta_time_ms    NUMBER(10),               -- Performance delta vs currently fitted set (ms)
    fitted               NUMBER(1),                -- Whether this set is currently on the car
    created_at           TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_tyre_sets PRIMARY KEY (session_uid, car_index, set_index),
    CONSTRAINT fk_tyreset_session FOREIGN KEY (session_uid)
        REFERENCES sessions (session_uid),
    CONSTRAINT fk_tyreset_participant FOREIGN KEY (session_uid, car_index)
        REFERENCES participants (session_uid, car_index)
);

-- =============================================================
-- 6. FINAL_CLASSIFICATIONS — one row per car per session
--    Sent once at end of session. Ground truth for validation.
-- =============================================================
CREATE TABLE final_classifications (
    session_uid            NUMBER(20)    NOT NULL,
    car_index              NUMBER(2)     NOT NULL,
    position               NUMBER(2)     NOT NULL,
    grid_position          NUMBER(2),
    num_laps               NUMBER(3),
    points                 NUMBER(2),
    num_pit_stops          NUMBER(2),
    result_status          NUMBER(1)     NOT NULL,   -- 0-7, see F1.md
    result_reason          NUMBER(2),                -- 0-10, see F1.md Retirement reasons
    best_lap_time_ms       NUMBER(10),
    total_race_time_s      NUMBER(12,3),             -- double precision seconds
    penalties_time_sec     NUMBER(3),
    num_penalties          NUMBER(2),
    num_tyre_stints        NUMBER(1),
    -- Tyre stint compounds (up to 8 stints)
    stint1_actual          NUMBER(2),
    stint1_visual          NUMBER(2),
    stint1_end_lap         NUMBER(3),
    stint2_actual          NUMBER(2),
    stint2_visual          NUMBER(2),
    stint2_end_lap         NUMBER(3),
    stint3_actual          NUMBER(2),
    stint3_visual          NUMBER(2),
    stint3_end_lap         NUMBER(3),
    stint4_actual          NUMBER(2),
    stint4_visual          NUMBER(2),
    stint4_end_lap         NUMBER(3),
    stint5_actual          NUMBER(2),
    stint5_visual          NUMBER(2),
    stint5_end_lap         NUMBER(3),
    stint6_actual          NUMBER(2),
    stint6_visual          NUMBER(2),
    stint6_end_lap         NUMBER(3),
    stint7_actual          NUMBER(2),
    stint7_visual          NUMBER(2),
    stint7_end_lap         NUMBER(3),
    stint8_actual          NUMBER(2),
    stint8_visual          NUMBER(2),
    stint8_end_lap         NUMBER(3),
    created_at             TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_final_class PRIMARY KEY (session_uid, car_index),
    CONSTRAINT fk_fclass_session FOREIGN KEY (session_uid)
        REFERENCES sessions (session_uid),
    CONSTRAINT fk_fclass_participant FOREIGN KEY (session_uid, car_index)
        REFERENCES participants (session_uid, car_index)
);

-- =============================================================
-- 7. CALIBRATION_COEFFICIENTS — fitted model values per knob
-- =============================================================
CREATE SEQUENCE seq_calibration_coefficients START WITH 1 INCREMENT BY 1;

CREATE TABLE calibration_coefficients (
    coefficient_id       NUMBER(12)    NOT NULL,
    track_id             NUMBER(3)     NOT NULL,
    knob_name            VARCHAR2(64)  NOT NULL,
    calibration_regime   VARCHAR2(6)   NOT NULL,  -- 'PLAYER' or 'AI'
    sector_number        NUMBER(1),               -- NULL if track-wide, 0/1/2 if sector-specific
    method_name          VARCHAR2(64)  NOT NULL,   -- e.g. 'linear_regression', 'polynomial_fit', 'ridge'
    value                NUMBER(12,6)  NOT NULL,
    confidence           NUMBER(8,4),              -- standard error or similar
    score                NUMBER(8,6),              -- R², RMSE, or other evaluation metric
    is_default           NUMBER(1)    DEFAULT 0 NOT NULL,
    session_count        NUMBER(5),
    data_point_count     NUMBER(10),
    game_settings_hash   VARCHAR2(64),
    trained_at           TIMESTAMP     NOT NULL,
    created_at           TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_calib_coeff PRIMARY KEY (coefficient_id),
    CONSTRAINT chk_calib_regime CHECK (calibration_regime IN ('PLAYER', 'AI')),
    CONSTRAINT chk_calib_sector CHECK (sector_number IS NULL OR sector_number IN (0, 1, 2)),
    CONSTRAINT chk_calib_default CHECK (is_default IN (0, 1))
);

-- =============================================================
-- 8. DRIVER_RATINGS — per-driver skill rating for outlier detection cold start
-- =============================================================
CREATE TABLE driver_ratings (
    driver_name      VARCHAR2(48)  NOT NULL,
    track_id         NUMBER(3)     DEFAULT -1 NOT NULL,  -- -1 = global default, real track_id = per-track override
    skill_rating     NUMBER(3)     NOT NULL,             -- 0 (inconsistent) to 100 (alien consistency)
    updated_at       TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_driver_ratings PRIMARY KEY (driver_name, track_id),
    CONSTRAINT chk_skill_range CHECK (skill_rating BETWEEN 0 AND 100)
);

-- =============================================================
-- SCHEMA ADDITIONS (applied via ALTER after initial DDL)
-- =============================================================

-- Outlier flag on sector_snapshots (recomputed each calibration run)
ALTER TABLE sector_snapshots ADD (
    outlier  NUMBER(1)  DEFAULT 0 NOT NULL
    CONSTRAINT chk_outlier CHECK (outlier IN (0, 1))
);

-- =============================================================
-- INDEXES
-- =============================================================

-- Events: query by session + event type (e.g., "all safety cars in session X")
CREATE INDEX idx_events_session_code
    ON session_events (session_uid, event_code);

-- Sector snapshots: analytical queries that filter by compound + validity
-- Covers: tyre degradation curves, base pace extraction
CREATE INDEX idx_sector_compound_valid
    ON sector_snapshots (tyre_compound_actual, lap_invalid, safety_car_status);

-- Sector snapshots: dirty air / DRS analysis (gap-based queries)
CREATE INDEX idx_sector_gap_ahead
    ON sector_snapshots (gap_to_car_ahead_ms);

-- Sector snapshots: outlier-aware calibration queries
CREATE INDEX idx_sector_outlier
    ON sector_snapshots (outlier, lap_invalid, safety_car_status, pit_status);

-- Calibration coefficients: lookup by track + knob + regime + method
CREATE INDEX idx_calib_lookup
    ON calibration_coefficients (track_id, knob_name, calibration_regime, method_name);

-- =============================================================
-- 9. DRIVERS — driver identity, decoupled from session participation
-- =============================================================
CREATE TABLE drivers (
    driver_id    NUMBER GENERATED ALWAYS AS IDENTITY,
    name         VARCHAR2(100) NOT NULL,
    email        VARCHAR2(255),
    created_at   TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_drivers PRIMARY KEY (driver_id),
    CONSTRAINT uq_drivers_name UNIQUE (name)
);

-- =============================================================
-- 10. DRIVER_SESSIONS — junction: which driver raced in which session
-- =============================================================
CREATE TABLE driver_sessions (
    driver_id    NUMBER        NOT NULL,
    session_uid  NUMBER(20)    NOT NULL,
    car_index    NUMBER(2)     NOT NULL,

    CONSTRAINT pk_driver_sessions PRIMARY KEY (driver_id, session_uid),
    CONSTRAINT fk_ds_driver FOREIGN KEY (driver_id)
        REFERENCES drivers (driver_id),
    CONSTRAINT fk_ds_participant FOREIGN KEY (session_uid, car_index)
        REFERENCES participants (session_uid, car_index)
);
```

## Simulation Query Mapping

| Simulation need                 | Query pattern                                                                                                   | Why the schema supports it                                                                   |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Base pace per driver per track  | `sector_snapshots JOIN sessions` filtered by `track_id`, `lap_invalid=0`, `pit_status=0`, `safety_car_status=0` | `idx_sector_compound_valid` covers the filter; join to sessions on PK is cheap               |
| Tyre degradation curves         | Group by `tyre_compound_actual`, `tyre_age_laps`; aggregate `sector_time_ms`                                    | Compound + age are flat columns, no joins needed beyond sessions for track filtering         |
| Fuel effect on pace             | `sector_time_ms` vs `fuel_in_tank_kg`, same filters as base pace                                                | Fuel is a direct column, same query path                                                     |
| Overtake probability per sector | Self-join: compare `car_position` between consecutive sectors for the same car                                  | PK index on `(session, car, lap, sector)` supports efficient lookups for consecutive sectors |
| Pit stop duration               | Compare in-lap sector 2 time + out-lap sector 0 time vs normal for same car/track                               | `pit_status` column identifies in/out laps directly                                          |
| Safety car / DNF probability    | Count `SCAR` and `RTMT` events per session, join to sessions for race distance                                  | Events table + session metadata                                                              |
| Dirty air modeling              | Filter `gap_to_car_ahead_ms < 1500`, compare sector_time vs clean-air baseline                                  | `idx_sector_gap_ahead` index covers this                                                     |
| DRS advantage                   | Filter `drs_allowed = 1`, compare sector times vs non-DRS sectors                                               | Flat column, no extra table needed                                                           |
| Weather effect                  | Compare sector times across `weather` values for same driver/track/compound                                     | Weather is denormalized per-snapshot, so transitions are visible                             |
| Simulation validation           | `final_classifications JOIN sessions` — compare predicted positions/times against actual                        | One row per car with ground truth result, strategy, and timing                               |
| Strategy ground truth           | `final_classifications` filtered by `result_status=3` — actual stint compounds and pit stop laps                | Flat stint columns give the full executed strategy without joins                             |
| Car damage effect on pace       | `sector_snapshots` damage columns correlated with `sector_time_ms`, filtered by `car_damage_setting`            | Flat damage columns per snapshot; game settings in `sessions` for conditioning               |
| Tyre temperature effect         | `sector_snapshots` tyre temp columns vs `sector_time_ms`, grouped by compound and weather                       | Surface + inner temps per wheel, denormalized alongside wear/damage                          |
| Calibration coefficient lookup  | `calibration_coefficients` filtered by `track_id`, `knob_name`, `calibration_regime`                            | `idx_calib_lookup` covers the query; `method_name` + `score` enable method comparison        |
| Outlier-clean calibration data  | All calibration queries add `AND outlier = 0` alongside existing hard filters                                   | `idx_sector_outlier` covers the filter; `outlier` column recomputed each calibration run     |
| Driver skill rating lookup      | `driver_ratings` filtered by `driver_name`, with `track_id` fallback (`-1` = global)                            | PK lookup; small table, always in buffer cache                                               |

## What Was Deliberately Left Out

- **No partitioning** — with hundreds of sessions and maybe low millions of sector rows, Oracle handles this fine without partition complexity. Add range partitioning on `created_at` later if volume grows.
- **No materialized views** — the simulation engine computes distributions offline. A materialized view for "average sector time by driver/track/compound/tyre_age" would be useful at scale, but premature for a POC.
- **No `stints` table** — stints are derived by detecting `tyre_compound_actual` changes or `num_pit_stops` increments in sector_snapshots. A view can formalize this if needed, but a table would be redundant data.
- **No `weather_changes` table** — weather transitions are visible by comparing consecutive sector snapshots' `weather` column. The events table captures safety car (which correlates with weather), but the game doesn't emit a weather-change event type.
- **No `calibration_sessions` junction table** — `calibration_coefficients` tracks `session_count` and `data_point_count` but not which specific sessions were used in training. A junction table could be added later if full reproducibility of training runs is needed.
