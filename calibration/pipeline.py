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
    _fit_sector_baselines(conn, track_id)


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
    by_sector: dict[int, list[tuple]] = defaultdict(list)
    for r in data:
        fuel = r[db._COL_FUEL]
        if fuel is not None and fuel > 0:
            by_sector[r[db._COL_SECTOR_NUMBER]].append(r)

    for sector in sorted(by_sector):
        group = by_sector[sector]
        if len(group) < MIN_FUEL_SAMPLES:
            print(f"  fuel_effect sector {sector}: insufficient data ({len(group)}), skipping")
            continue

        x = np.array([r[db._COL_FUEL] for r in group], dtype=float)
        y = np.array([r[db._COL_SECTOR_TIME_MS] for r in group], dtype=float)
        reg = linear_regression(x, y)

        db.insert_calibration_coefficient(
            conn, track_id, "fuel_effect", regime, sector, "linear_regression",
            reg.slope, 0, now)

        print(f"  fuel_effect sector {sector}: slope={reg.slope:.2f} ms/kg, n={reg.n}")


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


MIN_SECTOR_BASELINE_SAMPLES = 3   # sectors give ~3× the data; gate kept low for sparse FP
FUEL_BUCKET_KG = 20      # round to nearest 20 kg
TEMP_BUCKET_C = 10       # round to nearest 10 °C
MAD_OUTLIER_FACTOR = 2.5



_SELECT_SECTOR_BASELINE_DATA = """
    SELECT ss.sector_number, ss.tyre_compound_actual, p.ai_controlled,
           ss.fuel_in_tank_kg, ss.weather, ss.track_temp, ss.sector_time_ms
    FROM sector_snapshots ss
    JOIN participants p ON p.session_uid = ss.session_uid AND p.car_index = ss.car_index
    JOIN sessions s ON s.session_uid = ss.session_uid
    WHERE s.track_id = :1
      AND ss.lap_invalid = 0
      AND ss.corner_cutting_warnings = 0
      AND ss.pit_status = 0
      AND ss.safety_car_status = 0
      AND ss.outlier = 0
      AND ss.sector_time_ms > 0
      AND NOT (ss.lap_number = 1 AND ss.sector_number = 0)
      AND ss.session_type NOT IN (5, 6, 7, 8, 9)
"""


def _fit_sector_baselines(conn: oracledb.Connection, track_id: int) -> None:
    """Per-sector pace baselines from sector_snapshots, bucketed by
    (sector, compound, regime, fuel, weather, temp). Qualifying sessions are
    excluded (low-fuel push laps bias race pace). Stores mean / stddev / perfect
    (= min clean sector) per bucket with >= MIN_SECTOR_BASELINE_SAMPLES samples.
    """
    with conn.cursor() as cur:
        cur.execute(_SELECT_SECTOR_BASELINE_DATA, [track_id])
        rows = cur.fetchall()

    groups = _bucket_sector_rows(rows)
    now = datetime.now()
    written = 0
    for key, times in groups.items():
        filtered = _mad_filter(times)
        if len(filtered) < MIN_SECTOR_BASELINE_SAMPLES:
            continue
        m, sd, perfect = _summarize_bucket(filtered)
        sector, compound, regime, fuel_bucket, weather, temp_bucket = key
        _upsert_sector_baseline(
            conn, track_id, sector, compound, regime,
            fuel_bucket, weather, temp_bucket,
            m, sd, perfect, len(filtered), now,
        )
        written += 1
        print(
            f"  sector_baseline track={track_id} s{sector} compound={compound} "
            f"regime={regime} bucket=fuel{fuel_bucket}/w{weather}/t{temp_bucket} "
            f"mean={m:.0f}ms perfect={perfect:.0f}ms n={len(filtered)}"
        )

    if written == 0:
        print(f"  sector_baseline: no buckets with >= {MIN_SECTOR_BASELINE_SAMPLES} samples")



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


def _summarize_bucket(times: list[int]) -> tuple[float, float, float]:
    """(mean, population stddev, perfect=min) for one bucket of sector times."""
    arr = np.array(times, dtype=float)
    return mean(arr), sqrt(variance(arr)), float(min(times))


def _bucket_sector_rows(rows: list[tuple]) -> dict[tuple, list[int]]:
    """Group raw sector rows into buckets keyed by
    (sector, compound, regime, fuel_bucket, weather, temp_bucket). Rows missing
    any bucketing field are skipped — they can't be aggregated meaningfully.

    Row order matches _SELECT_SECTOR_BASELINE_DATA:
    (sector_number, compound, ai_controlled, fuel_kg, weather, track_temp, sector_time_ms).
    """
    groups: dict[tuple, list[int]] = defaultdict(list)
    for sector, compound, ai_controlled, fuel_kg, weather, temp_c, time_ms in rows:
        if (ai_controlled is None or fuel_kg is None
                or weather is None or temp_c is None):
            continue
        fuel_bucket = int(round(float(fuel_kg) / FUEL_BUCKET_KG) * FUEL_BUCKET_KG)
        temp_bucket = int(round(float(temp_c) / TEMP_BUCKET_C) * TEMP_BUCKET_C)
        regime = "AI" if int(ai_controlled) == 1 else "PLAYER"
        key = (int(sector), int(compound), regime, fuel_bucket, int(weather), temp_bucket)
        groups[key].append(int(time_ms))
    return groups


def _upsert_sector_baseline(
    conn: oracledb.Connection,
    track_id: int, sector_number: int, compound: int, regime: str,
    fuel_bucket_kg: int, weather: int, track_temp_bucket_c: int,
    mean_sector_ms: float, stddev_sector_ms: float, perfect_sector_ms: float,
    sample_count: int, fitted_at: datetime,
) -> None:
    with conn.cursor() as cur:
        # Named binds (not :1..:N) to dedupe placeholders across the MERGE
        # clauses — oracledb counts each occurrence of a numbered placeholder
        # as a distinct positional value (DPY-4009); named binds dedupe by name.
        cur.execute(
            """
            MERGE INTO sector_pace_baselines b
            USING (SELECT :track_id track_id, :sector_number sector_number,
                          :compound compound, :regime regime,
                          :fuel_bucket_kg fuel_bucket_kg, :weather weather,
                          :track_temp_bucket_c track_temp_bucket_c FROM dual) s
            ON (b.track_id = s.track_id AND b.sector_number = s.sector_number
                AND b.compound = s.compound AND b.regime = s.regime
                AND b.fuel_bucket_kg = s.fuel_bucket_kg AND b.weather = s.weather
                AND b.track_temp_bucket_c = s.track_temp_bucket_c)
            WHEN MATCHED THEN UPDATE SET
                mean_sector_ms = :mean_sector_ms,
                stddev_sector_ms = :stddev_sector_ms,
                perfect_sector_ms = :perfect_sector_ms,
                sample_count = :sample_count, last_fitted_at = :last_fitted_at
            WHEN NOT MATCHED THEN INSERT
                (track_id, sector_number, compound, regime, fuel_bucket_kg,
                 weather, track_temp_bucket_c, mean_sector_ms, stddev_sector_ms,
                 perfect_sector_ms, sample_count, last_fitted_at)
                VALUES (:track_id, :sector_number, :compound, :regime,
                        :fuel_bucket_kg, :weather, :track_temp_bucket_c,
                        :mean_sector_ms, :stddev_sector_ms, :perfect_sector_ms,
                        :sample_count, :last_fitted_at)
            """,
            {
                "track_id": track_id, "sector_number": sector_number,
                "compound": compound, "regime": regime,
                "fuel_bucket_kg": fuel_bucket_kg, "weather": weather,
                "track_temp_bucket_c": track_temp_bucket_c,
                "mean_sector_ms": int(round(mean_sector_ms)),
                "stddev_sector_ms": int(round(stddev_sector_ms)),
                "perfect_sector_ms": int(round(perfect_sector_ms)),
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
