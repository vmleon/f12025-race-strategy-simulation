from __future__ import annotations

from collections import defaultdict
from datetime import datetime
from math import sqrt

import numpy as np
import oracledb

from calibration import db
from calibration.fitting import linear_regression, mean, variance
from calibration.outlier_detector import detect_outliers

MIN_BASE_PACE_SAMPLES = 5
MIN_TYRE_DEG_SAMPLES = 10
MIN_FUEL_SAMPLES = 5
MIN_PIT_STOP_SAMPLES = 3

MAX_TYRE_AGE_CLEAN = 5
MIN_GAP_CLEAN_AIR_MS = 2000

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
    ratings = db.get_driver_ratings(conn)
    outliers = detect_outliers(entries, ratings)
    db.update_outlier_flags(conn, track_id, outliers)
    conn.commit()
    print(f"Outlier detection: {len(outliers)} flagged out of {len(entries)} sectors")

    session_count = db.get_session_count_for_track(conn, track_id)

    # Step 2: fit each regime
    for regime in ["PLAYER", "AI"]:
        data = db.get_calibration_data(conn, track_id, regime)
        if not data:
            print(f"No data for regime {regime}, skipping")
            continue

        settings_hash = _compute_settings_hash(data[0])
        now = datetime.now()
        print(f"Regime {regime}: {len(data)} data points, {session_count} sessions")

        _fit_base_pace(conn, data, track_id, regime, settings_hash, session_count, now)
        _fit_tyre_degradation(conn, data, track_id, regime, settings_hash, session_count, now)
        _fit_fuel_effect(conn, data, track_id, regime, settings_hash, session_count, now)
        _fit_pit_stop_duration(conn, track_id, regime, settings_hash, session_count, now)


# ── base pace ────────────────────────────────────────────────────────


def _fit_base_pace(
    conn: oracledb.Connection, data: list[tuple], track_id: int, regime: str,
    settings_hash: str, session_count: int, now: datetime,
) -> None:
    by_sector = _group_by_sector(data)

    for sector, sector_data in by_sector.items():
        clean = [r for r in sector_data
                 if r[db._COL_TYRE_AGE] <= MAX_TYRE_AGE_CLEAN
                 and (r[db._COL_GAP_AHEAD] > MIN_GAP_CLEAN_AIR_MS or r[db._COL_GAP_AHEAD] == 0)
                 and _has_no_damage(r)]

        source = clean if len(clean) >= MIN_BASE_PACE_SAMPLES else sector_data
        if len(source) < MIN_BASE_PACE_SAMPLES:
            print(f"  base_pace sector {sector}: insufficient data ({len(source)}), skipping")
            continue

        times = np.array([r[db._COL_SECTOR_TIME_MS] for r in source], dtype=float)
        m = mean(times)
        v = variance(times)
        sd = sqrt(v)
        std_error = sd / sqrt(len(times))

        db.insert_calibration_coefficient(
            conn, track_id, "base_pace_mean", regime, sector, "mean",
            m, std_error, None, 0, session_count, len(times), settings_hash, now)
        db.insert_calibration_coefficient(
            conn, track_id, "base_pace_std_dev", regime, sector, "std_dev",
            sd, None, None, 0, session_count, len(times), settings_hash, now)

        print(f"  base_pace sector {sector}: mean={m:.1f}ms, sd={sd:.1f}ms, n={len(times)} (clean={len(clean)})")


# ── tyre degradation ─────────────────────────────────────────────────


def _fit_tyre_degradation(
    conn: oracledb.Connection, data: list[tuple], track_id: int, regime: str,
    settings_hash: str, session_count: int, now: datetime,
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
            reg.slope, reg.slope_std_error, reg.r_squared, 0,
            session_count, reg.n, settings_hash, now)

        print(f"  {knob_name} sector {sector}: slope={reg.slope:.2f} ms/lap, R²={reg.r_squared:.4f}, n={reg.n}")


# ── fuel effect ──────────────────────────────────────────────────────


def _fit_fuel_effect(
    conn: oracledb.Connection, data: list[tuple], track_id: int, regime: str,
    settings_hash: str, session_count: int, now: datetime,
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
        reg.slope, reg.slope_std_error, reg.r_squared, 0,
        session_count, reg.n, settings_hash, now)

    print(f"  fuel_effect: slope={reg.slope:.2f} ms/kg, R²={reg.r_squared:.4f}, n={reg.n}")


# ── pit stop duration ────────────────────────────────────────────────


def _fit_pit_stop_duration(
    conn: oracledb.Connection, track_id: int, regime: str,
    settings_hash: str, session_count: int, now: datetime,
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
        m, None, None, 0, session_count, len(time_losses), settings_hash, now)
    db.insert_calibration_coefficient(
        conn, track_id, "pit_stop_time_loss_stddev", regime, None, "stddev",
        sd, None, None, 0, session_count, len(time_losses), settings_hash, now)

    print(f"  pit_stop_time_loss: mean={m:.0f}ms, sd={sd:.0f}ms, n={len(time_losses)}")


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


# ── helpers ──────────────────────────────────────────────────────────


def _compute_settings_hash(row: tuple) -> str:
    key = f"{row[db._COL_AI_DIFF]}|{row[db._COL_CAR_DMG_SET]}|{row[db._COL_CAR_DMG_RATE]}|{row[db._COL_LOW_FUEL]}"
    return format(hash(key) & 0xFFFFFFFF, "x")


def _group_by_sector(data: list[tuple]) -> dict[int, list[tuple]]:
    groups: dict[int, list[tuple]] = defaultdict(list)
    for r in data:
        groups[r[db._COL_SECTOR_NUMBER]].append(r)
    return dict(groups)


def _has_no_damage(r: tuple) -> bool:
    return (r[db._COL_FW_DMG_L] == 0 and r[db._COL_FW_DMG_R] == 0
            and r[db._COL_RW_DMG] == 0 and r[db._COL_FLOOR_DMG] == 0
            and r[db._COL_DIFF_DMG] == 0 and r[db._COL_SIDE_DMG] == 0
            and r[db._COL_ENG_DMG] == 0 and r[db._COL_GEAR_DMG] == 0)
