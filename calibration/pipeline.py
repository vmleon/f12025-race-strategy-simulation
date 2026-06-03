from __future__ import annotations

from collections import defaultdict
from datetime import datetime
from math import sqrt

import numpy as np
import oracledb

from calibration import db
from calibration.fitting import linear_regression, mean, variance
from calibration.outlier_detector import detect_outliers

MIN_TYRE_DEG_SAMPLES = 10
MIN_FUEL_SAMPLES = 5
MIN_PIT_STOP_SAMPLES = 3

COMPOUND_KNOB_NAMES = {
    16: "tyre_deg_soft",
    17: "tyre_deg_medium",
    18: "tyre_deg_hard",
}


def run(conn: oracledb.Connection, track_id: int) -> None:
    # Step 0: ensure cold-start defaults
    defaults_inserted = db.ensure_cold_start_defaults(conn, track_id)
    if defaults_inserted > 0:
        print(f"Inserted {defaults_inserted} cold-start defaults")

    # Step 1: recompute outlier flags
    entries = db.get_sectors_for_outlier_detection(conn, track_id)
    outliers = detect_outliers(entries)
    db.update_outlier_flags(conn, track_id, outliers)
    conn.commit()
    print(f"Outlier detection: {len(outliers)} flagged out of {len(entries)} sectors")

    # Step 2: fit each regime
    for regime in ["PLAYER", "AI"]:
        data = db.get_calibration_data(conn, track_id, regime)
        if not data:
            print(f"No data for regime {regime}, skipping")
            continue

        now = datetime.now()
        print(f"Regime {regime}: {len(data)} data points")

        _fit_tyre_degradation(conn, data, track_id, regime, now)
        _fit_fuel_effect(conn, data, track_id, regime, now)
        _fit_pit_stop_duration(conn, track_id, regime, now)

    # Pace baselines aggregate across regimes inside the function — single call.
    _fit_pace_baselines(conn, track_id)


# ── tyre degradation ─────────────────────────────────────────────────


def _fit_tyre_degradation(
    conn: oracledb.Connection, data: list[tuple], track_id: int, regime: str,
    now: datetime,
) -> None:
    groups: dict[str, list[tuple]] = defaultdict(list)
    for r in data:
        knob_name = COMPOUND_KNOB_NAMES.get(r[db._COL_TYRE_COMPOUND])
        if knob_name is None:
            continue
        key = f"{r[db._COL_SECTOR_NUMBER]}|{knob_name}"
        groups[key].append(r)

    for group_key, group in groups.items():
        sector_str, knob_name = group_key.split("|")
        sector = int(sector_str)

        if len(group) < MIN_TYRE_DEG_SAMPLES:
            print(f"  {knob_name} sector {sector}: insufficient data ({len(group)}), skipping")
            continue

        x = np.array([r[db._COL_TYRE_AGE] for r in group], dtype=float)
        y = np.array([r[db._COL_SECTOR_TIME_MS] for r in group], dtype=float)
        reg = linear_regression(x, y)

        db.insert_calibration_coefficient(
            conn, track_id, knob_name, regime, sector, "linear_regression",
            reg.slope, 0, now)

        print(f"  {knob_name} sector {sector}: slope={reg.slope:.2f} ms/lap, n={reg.n}")


# ── fuel effect ──────────────────────────────────────────────────────


def _fit_fuel_effect(
    conn: oracledb.Connection, data: list[tuple], track_id: int, regime: str,
    now: datetime,
) -> None:
    with_fuel = [r for r in data if r[db._COL_FUEL] > 0]

    if len(with_fuel) < MIN_FUEL_SAMPLES:
        print(f"  fuel_effect: insufficient data ({len(with_fuel)}), skipping")
        return

    x = np.array([r[db._COL_FUEL] for r in with_fuel], dtype=float)
    y = np.array([r[db._COL_SECTOR_TIME_MS] for r in with_fuel], dtype=float)
    reg = linear_regression(x, y)

    db.insert_calibration_coefficient(
        conn, track_id, "fuel_effect", regime, None, "linear_regression",
        reg.slope, 0, now)

    print(f"  fuel_effect: slope={reg.slope:.2f} ms/kg, n={reg.n}")


# ── pit stop duration ────────────────────────────────────────────────


def _fit_pit_stop_duration(
    conn: oracledb.Connection, track_id: int, regime: str,
    now: datetime,
) -> None:
    pit_sectors = db.get_pit_stop_sectors(conn, track_id)
    baselines = db.get_normal_sector_medians(conn, track_id)

    ai_controlled = 1 if regime == "AI" else 0
    pit_sectors = [s for s in pit_sectors if s[db.PIT_COL_AI] == ai_controlled]

    stops = _group_pit_stops(pit_sectors)

    time_losses = []
    for stop in stops:
        loss = 0.0
        valid = True
        for sector in stop:
            baseline = baselines.get((sector[db.PIT_COL_SECTOR], ai_controlled))
            if baseline is None or baseline <= 0:
                valid = False
                break
            loss += sector[db.PIT_COL_TIME] - baseline
        if valid and loss > 0:
            time_losses.append(loss)

    if len(time_losses) < MIN_PIT_STOP_SAMPLES:
        print(f"  pit_stop_time_loss: insufficient data ({len(time_losses)}), skipping")
        return

    arr = np.array(time_losses, dtype=float)
    m = mean(arr)
    sd = sqrt(variance(arr))

    db.insert_calibration_coefficient(
        conn, track_id, "pit_stop_time_loss", regime, None, "mean",
        m, 0, now)
    db.insert_calibration_coefficient(
        conn, track_id, "pit_stop_time_loss_stddev", regime, None, "stddev",
        sd, 0, now)

    print(f"  pit_stop_time_loss: mean={m:.0f}ms, sd={sd:.0f}ms, n={len(time_losses)}")


# ── pace baselines ──────────────────────────────────────────────────
#
# Aggregate raw lap times from `lap_pace_observations` into per-bucket means
# keyed by (track, compound, regime, fuel bucket, weather, temp bucket).
# Consumed by the simulator as the cold-start / post-pit-stop fallback below
# observed laps, above the per-circuit default.

MIN_PACE_BASELINE_SAMPLES = 5
FUEL_BUCKET_KG = 20      # round to nearest 20 kg
TEMP_BUCKET_C = 10       # round to nearest 10 °C
MAD_OUTLIER_FACTOR = 2.5


def _fit_pace_baselines(conn: oracledb.Connection, track_id: int) -> None:
    rows: list[tuple] = []
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT compound, ai_controlled, fuel_kg, weather, track_temp_c, lap_time_ms
            FROM lap_pace_observations
            WHERE track_id = :1 AND lap_time_ms > 0
            """,
            [track_id],
        )
        rows = cur.fetchall()

    groups = _bucket_pace_rows(rows)
    now = datetime.now()
    written = 0
    for key, times in groups.items():
        filtered = _mad_filter(times)
        if len(filtered) < MIN_PACE_BASELINE_SAMPLES:
            continue
        m = mean(np.array(filtered, dtype=float))
        sd = sqrt(variance(np.array(filtered, dtype=float)))
        compound, regime, fuel_bucket, weather, temp_bucket = key
        _upsert_pace_baseline(
            conn, track_id, compound, regime, fuel_bucket, weather, temp_bucket,
            m, sd, len(filtered), now,
        )
        written += 1
        print(
            f"  pace_baseline track={track_id} compound={compound} regime={regime} "
            f"bucket=fuel{fuel_bucket}/w{weather}/t{temp_bucket} "
            f"mean={m:.0f}ms sd={sd:.0f}ms n={len(filtered)}"
        )

    if written == 0:
        print(f"  pace_baseline: no buckets with >= {MIN_PACE_BASELINE_SAMPLES} samples")


def _bucket_pace_rows(rows: list[tuple]) -> dict[tuple, list[int]]:
    """Group raw observations into buckets keyed by
    (compound, regime, fuel_bucket, weather, temp_bucket). Rows missing any
    bucketing field are skipped — we can't aggregate them meaningfully.
    """
    groups: dict[tuple, list[int]] = defaultdict(list)
    for compound, ai_controlled, fuel_kg, weather, track_temp_c, lap_ms in rows:
        if (ai_controlled is None or fuel_kg is None
                or weather is None or track_temp_c is None):
            continue
        fuel_bucket = int(round(float(fuel_kg) / FUEL_BUCKET_KG) * FUEL_BUCKET_KG)
        temp_bucket = int(round(float(track_temp_c) / TEMP_BUCKET_C) * TEMP_BUCKET_C)
        regime = "AI" if int(ai_controlled) == 1 else "PLAYER"
        key = (int(compound), regime, fuel_bucket, int(weather), temp_bucket)
        groups[key].append(int(lap_ms))
    return groups


def _mad_filter(times: list[int]) -> list[int]:
    """Drop laps whose deviation from the median exceeds MAD_OUTLIER_FACTOR × MAD.
    Robust to a small number of off-track / pit-loss rows that slipped through.
    """
    if len(times) < 3:
        return list(times)
    arr = np.array(times, dtype=float)
    median = float(np.median(arr))
    mad = float(np.median(np.abs(arr - median)))
    if mad == 0:
        return list(times)
    threshold = MAD_OUTLIER_FACTOR * mad
    return [t for t in times if abs(t - median) <= threshold]


def _upsert_pace_baseline(
    conn: oracledb.Connection,
    track_id: int, compound: int, regime: str,
    fuel_bucket_kg: int, weather: int, track_temp_bucket_c: int,
    mean_lap_ms: float, stddev_lap_ms: float, sample_count: int,
    fitted_at: datetime,
) -> None:
    with conn.cursor() as cur:
        # Named binds (not :1..:N): oracledb counts each *occurrence* of a
        # numbered placeholder as a separate positional value, so reusing the
        # same number across the USING / UPDATE / INSERT clauses requires 20
        # values for 10 logical binds (DPY-4009). Named binds dedupe by name.
        cur.execute(
            """
            MERGE INTO lap_pace_baselines b
            USING (SELECT :track_id track_id, :compound compound, :regime regime,
                          :fuel_bucket_kg fuel_bucket_kg, :weather weather,
                          :track_temp_bucket_c track_temp_bucket_c FROM dual) s
            ON (b.track_id = s.track_id AND b.compound = s.compound
                AND b.regime = s.regime AND b.fuel_bucket_kg = s.fuel_bucket_kg
                AND b.weather = s.weather AND b.track_temp_bucket_c = s.track_temp_bucket_c)
            WHEN MATCHED THEN UPDATE SET
                mean_lap_ms = :mean_lap_ms, stddev_lap_ms = :stddev_lap_ms,
                sample_count = :sample_count, last_fitted_at = :last_fitted_at
            WHEN NOT MATCHED THEN INSERT
                (track_id, compound, regime, fuel_bucket_kg, weather, track_temp_bucket_c,
                 mean_lap_ms, stddev_lap_ms, sample_count, last_fitted_at)
                VALUES (:track_id, :compound, :regime, :fuel_bucket_kg, :weather,
                        :track_temp_bucket_c, :mean_lap_ms, :stddev_lap_ms,
                        :sample_count, :last_fitted_at)
            """,
            {
                "track_id": track_id, "compound": compound, "regime": regime,
                "fuel_bucket_kg": fuel_bucket_kg, "weather": weather,
                "track_temp_bucket_c": track_temp_bucket_c,
                "mean_lap_ms": int(round(mean_lap_ms)),
                "stddev_lap_ms": int(round(stddev_lap_ms)),
                "sample_count": sample_count, "last_fitted_at": fitted_at,
            },
        )
    conn.commit()


def _group_pit_stops(pit_sectors: list[tuple]) -> list[list[tuple]]:
    """Group pit sectors into individual pit stop events.

    A pit stop starts with pit_status=1 (pitting). Subsequent pit_status=2
    sectors for the same car in the same session are part of the same stop.
    """
    stops: list[list[tuple]] = []
    current: list[tuple] = []

    for s in pit_sectors:
        if s[db.PIT_COL_STATUS] == 1:
            if current:
                stops.append(current)
            current = [s]
        elif (current
              and s[db.PIT_COL_CAR] == current[0][db.PIT_COL_CAR]
              and s[db.PIT_COL_SESSION] == current[0][db.PIT_COL_SESSION]):
            current.append(s)
        else:
            if current:
                stops.append(current)
            current = []

    if current:
        stops.append(current)

    return stops
