# Intentional Denormalization in the Database Schema

## The Question

The `sector_snapshots` table has ~60 columns including weather conditions, tyre wear for 4 wheels, tyre damage, tyre blisters, car damage for 8 components, tyre temperatures for 4 wheels (surface + inner), brake temperatures, and engine temperature. Shouldn't these be normalized into separate tables?

## Decision: Denormalize Everything Into `sector_snapshots`

This is intentional, not accidental. Each case has a specific reason.

### Weather, track temp, air temp

Weather changes mid-race. Track temperature changes every few minutes. If these values lived only in the `sessions` table, you'd lose the temporal dimension — you wouldn't know that lap 15 sector 2 was driven in rain while lap 14 sector 3 was dry.

Each sector snapshot captures the conditions **at that moment**. The simulation's lap time function includes weather and temperature as variables: `lap_time = f(..., weather, track_temp, air_temp, ...)`. Without per-snapshot conditions, this function can't work.

### Tyre wear, damage, and blisters (12 columns: 4 wheels x 3 metrics)

A normalized `tyre_wheel_data` child table would mean 4 child rows per sector snapshot. Every analytical query — tyre degradation curves, damage correlation, calibration fitting — would need a pivot or join to reconstruct the 4-wheel view.

The data is:
- Always exactly 4 values (one per wheel: RL, RR, FL, FR)
- Always read together (no query ever needs just one wheel)
- Never sparse (all 4 are always populated)

Flat columns are correct. The join overhead and pivot complexity of a child table add cost with zero benefit.

### Car damage (8 columns)

Same reasoning. Front wing L/R, rear wing, floor, diffuser, sidepod, engine, gearbox — always exactly 8 values, always read together for damage-effect calibration, never sparse.

### Tyre temperatures (8 columns: 4 wheels x surface + inner)

Same reasoning. Surface and inner temperatures for RL, RR, FL, FR. The calibration pipeline correlates these with sector times to fit tyre temperature sensitivity coefficients. Always 8 values, always together.

### Brake temperatures (4 columns) and engine temperature (1 column)

Same pattern. Fixed shape, always populated, always read as a group.

## What About `track_id`?

It was tempting to denormalize `track_id` into `sector_snapshots` since the most common analytical pattern is "all data for track X." But `sessions` will have hundreds of rows, not millions. The join `sector_snapshots JOIN sessions USING (session_uid) WHERE track_id = ?` hits the sessions primary key, resolves to a set of session UIDs, then scans sector_snapshots by PK prefix. Oracle handles this trivially.

The denormalization would save one cheap join at the cost of an extra column on every row of the highest-volume table, plus a potential update path if a track_id is ever corrected.

## What About Stints?

No `stints` table exists. Stints are derived by detecting `tyre_compound_actual` changes or `num_pit_stops` increments in sector_snapshots. A view can formalize this if needed, but a table would be redundant data that needs to be kept in sync.

## Key Takeaway

Normalization is a tool, not a rule. When the data has a fixed shape, is always read together, and needs to be correlated with the parent row's timing data, denormalization is the correct design. The overhead of joins and pivots for data that is always exactly 4 or 8 values adds complexity for no benefit.
