from __future__ import annotations

from collections import defaultdict
from datetime import datetime
from math import sqrt

import numpy as np
import oracledb

from calibration import db
from calibration.cold_start import KNOB_DEFAULTS
from calibration.fitting import linear_regression, mean, variance
from calibration.outlier_detector import detect_outliers

MIN_TYRE_DEG_SAMPLES = 10
MIN_FUEL_SAMPLES = 5
MIN_PIT_STOP_SAMPLES = 3
MIN_WEAR_SAMPLES = 5   # wear is smooth/monotonic, so it calibrates from few laps
PIT_INFLATION_FACTOR = 0.30   # a following sector joins the stop while this far over baseline
MAX_PIT_STOP_SECTORS = 4      # safety cap on sectors attributed to one stop

# Plausibility clamps for fitted slopes (ms). Thin / early FP data, where fuel burn
# confounds tyre wear over a short stint, can yield absurd or negative slopes — a few
# noisy laps once fit a soft-deg slope of ~1500 ms/lap (≈4.5 s/lap), which made the
# simulator pit far too early. Outside these bounds we fall back to the cold-start
# prior instead of poisoning the simulator with an over-fit value.
MAX_TYRE_DEG_MS_PER_LAP = 300.0     # per sector; ~0.9 s/lap total — generous upper bound
MAX_FUEL_EFFECT_MS_PER_KG = 50.0    # per sector; ~0.05 s/kg — generous upper bound
_PRIOR = dict(KNOB_DEFAULTS)        # cold-start defaults, used as the fallback prior

COMPOUND_KNOB_NAMES = {
    16: "tyre_deg_soft",
    17: "tyre_deg_medium",
    18: "tyre_deg_hard",
}

WEAR_RATE_KNOB_NAMES = {
    16: "tyre_wear_rate_soft",
    17: "tyre_wear_rate_medium",
    18: "tyre_wear_rate_hard",
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

    # Step 2: pace baselines first (the age-profile fit subtracts them)
    _fit_sector_baselines(conn, track_id)
    baselines = db.get_sector_baselines(conn, track_id)

    # Step 3: fit each regime
    for regime in ["PLAYER", "AI"]:
        data = db.get_calibration_data(conn, track_id, regime)
        if not data:
            print(f"No data for regime {regime}, skipping")
            continue
        now = datetime.now()
        print(f"Regime {regime}: {len(data)} data points")
        _fit_tyre_age_profile(conn, data, track_id, regime, baselines, now)
        _fit_tyre_wear_rate(conn, data, track_id, regime, now)
        _fit_fuel_effect(conn, data, track_id, regime, now)
        _fit_pit_stop_duration(conn, track_id, regime, now)
    conn.commit()


# ── tyre wear rate ───────────────────────────────────────────────────


def _most_worn(r: tuple) -> float | None:
    """Highest of the four per-wheel wear %, ignoring NULLs. None if all NULL."""
    vals = [r[db._COL_WEAR_FL], r[db._COL_WEAR_FR],
            r[db._COL_WEAR_RL], r[db._COL_WEAR_RR]]
    vals = [v for v in vals if v is not None]
    return max(vals) if vals else None


def _fit_tyre_wear_rate(
    conn: oracledb.Connection, data: list[tuple], track_id: int, regime: str,
    now: datetime,
) -> None:
    """Fit wear-rate (%/lap) per compound: slope of the most-worn wheel's wear vs
    tyre age. Stored sector-wide (sector=None). Feeds the simulator's laps-to-cliff
    stint cap. Clamped to >= 0 (wear can't fall with age); a 0 slope makes the
    simulator fall back to the hardcoded lifespan."""
    groups: dict[str, list[tuple]] = defaultdict(list)
    for r in data:
        knob_name = WEAR_RATE_KNOB_NAMES.get(r[db._COL_TYRE_COMPOUND])
        if knob_name is None:
            continue
        groups[knob_name].append(r)

    for knob_name, group in groups.items():
        pts = [(r[db._COL_TYRE_AGE], _most_worn(r)) for r in group]
        pts = [(age, wear) for age, wear in pts if wear is not None]
        if len(pts) < MIN_WEAR_SAMPLES:
            print(f"  {knob_name}: insufficient data ({len(pts)}), skipping")
            continue

        x = np.array([age for age, _ in pts], dtype=float)
        y = np.array([wear for _, wear in pts], dtype=float)
        reg = linear_regression(x, y)
        wear_rate = max(reg.slope, 0.0)
        clamped = 1 if reg.slope < 0.0 else 0  # negative wear is impossible → rejected

        db.insert_calibration_coefficient(
            conn, track_id, knob_name, regime, None, "linear_regression",
            wear_rate, 0, now, sample_count=reg.n, r_squared=reg.r_squared,
            slope_std_error=reg.slope_std_error, clamped=clamped)

        print(f"  {knob_name}: {wear_rate:.3f} %/lap, n={reg.n}")


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

        # A heavier car is slower, so fuel_effect must be >= 0; a negative slope means
        # fuel burn was confounded by track evolution / tyre warm-up on thin data.
        slope = reg.slope
        clamped = 0
        if not (0.0 <= slope <= MAX_FUEL_EFFECT_MS_PER_KG):
            prior = _PRIOR.get("fuel_effect", 10.0)
            print(f"  fuel_effect sector {sector}: implausible slope {slope:.1f} ms/kg "
                  f"(n={reg.n}) — falling back to prior {prior}")
            slope = prior
            clamped = 1

        db.insert_calibration_coefficient(
            conn, track_id, "fuel_effect", regime, sector, "linear_regression",
            slope, 0, now, sample_count=reg.n, r_squared=reg.r_squared,
            slope_std_error=reg.slope_std_error, clamped=clamped)

        print(f"  fuel_effect sector {sector}: slope={slope:.2f} ms/kg, n={reg.n}")


# ── pit stop duration ────────────────────────────────────────────────


def _fit_pit_stop_duration(
    conn: oracledb.Connection, track_id: int, regime: str,
    now: datetime,
) -> None:
    pit_sectors = db.get_pit_stop_sectors(conn, track_id)
    baselines = db.get_normal_sector_medians(conn, track_id)

    ai_controlled = 1 if regime == "AI" else 0
    pit_sectors = [s for s in pit_sectors if s[db.PIT_COL_AI] == ai_controlled]

    time_losses = _pit_stop_losses(pit_sectors, baselines, ai_controlled)

    if len(time_losses) < MIN_PIT_STOP_SAMPLES:
        print(f"  pit_stop_time_loss: insufficient data ({len(time_losses)}), skipping")
        return

    arr = np.array(time_losses, dtype=float)
    m = mean(arr)

    # Pit-lane time loss is deterministic per track (lane length + speed limit), so we
    # persist only the mean — the engine adds it straight onto race time, no stddev.
    # Non-regression knob: sample_count carries meaning, R²/SE do not (stay NULL).
    db.insert_calibration_coefficient(
        conn, track_id, "pit_stop_time_loss", regime, None, "mean",
        m, 0, now, sample_count=len(time_losses))

    print(f"  pit_stop_time_loss: mean={m:.0f}ms, n={len(time_losses)}")


MIN_SECTOR_BASELINE_SAMPLES = 3   # sectors give ~3× the data; gate kept low for sparse FP
FUEL_BUCKET_KG = 20      # round to nearest 20 kg
TEMP_BUCKET_C = 10       # round to nearest 10 °C
MAD_OUTLIER_FACTOR = 2.5
MIN_AGE_BIN_SAMPLES = 5
WARMUP_END_AGE = 3   # tail deg slope is fitted from this stint age onward

# Visual compound codes: 16=soft, 17=medium, 18=hard, 7=inter, 8=wet.
# Matches CHK_SECTOR_BASELINE_COMPOUND; excludes 0 garbage and out-of-range codes.
VALID_VISUAL_COMPOUNDS = frozenset({7, 8, 16, 17, 18})



_SELECT_SECTOR_BASELINE_DATA = """
    SELECT ss.sector_number, ss.tyre_compound_visual, p.ai_controlled,
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
      AND NOT (ss.lap_number = 1 AND ss.sector_number = 0
               AND ss.session_type IN (10, 11, 12, 15, 16, 17))
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

    # Commit once, after every bucket is written, so a mid-loop failure (e.g. a
    # bad row) rolls back the whole fit instead of leaving a half-finished one.
    conn.commit()



def _bin_age_residuals(rows: list[tuple]) -> dict[tuple, list[float]]:
    """Group (compound, sector, age, residual_ms) rows by (compound, sector, age),
    dropping age 0 (out-lap, owned by the pit-loss model)."""
    bins: dict[tuple, list[float]] = defaultdict(list)
    for compound, sector, age, residual in rows:
        if age is None or age < 1:
            continue
        bins[(compound, sector, age)].append(residual)
    return bins


def _interpolate_gaps(ages: dict[int, float]) -> dict[int, float]:
    """Linearly fill interior missing ages between min and max. Returns only the
    newly interpolated ages (callers persist them with is_extrapolation=1)."""
    if len(ages) < 2:
        return {}
    lo, hi = min(ages), max(ages)
    filled: dict[int, float] = {}
    for a in range(lo + 1, hi):
        if a in ages:
            continue
        below = max(k for k in ages if k < a)
        above = min(k for k in ages if k > a)
        t = (a - below) / (above - below)
        filled[a] = ages[below] + t * (ages[above] - ages[below])
    return filled


def _tail_slope(ages: dict[int, float], warmup_end: int, prior: float, max_slope: float) -> float:
    """Deg-phase slope (ms/lap) from age >= warmup_end bins, clamped to [0, max_slope].
    Falls back to the cold-start prior when there are < 2 late bins."""
    late = sorted(a for a in ages if a >= warmup_end)
    if len(late) < 2:
        return prior
    xs = np.array(late, dtype=float)
    ys = np.array([ages[a] for a in late], dtype=float)
    slope = float(np.polyfit(xs, ys, 1)[0])
    return min(max(slope, 0.0), max_slope)


def _fit_tyre_age_profile(
    conn, data: list[tuple], track_id: int, regime: str,
    baselines: dict[tuple, float], now,
) -> None:
    """Per-(compound, sector, age) pace offset vs the sector_pace_baseline. Keeps
    ALL stint laps (warm-up included); only age 0 (out-lap) is excluded. Writes the
    offset table and the tail deg slope (into tyre_deg_*) for extrapolation."""
    rows: list[tuple] = []
    for r in data:
        comp = r[db._COL_TYRE_COMPOUND]
        if comp not in COMPOUND_KNOB_NAMES:
            continue
        fuel, weather, temp = r[db._COL_FUEL], r[db._COL_WEATHER], r[db._COL_TRACK_TEMP]
        if fuel is None or weather is None or temp is None:
            continue
        key = (r[db._COL_SECTOR_NUMBER], comp, regime,
               int(round(float(fuel) / FUEL_BUCKET_KG) * FUEL_BUCKET_KG),
               int(weather),
               int(round(float(temp) / TEMP_BUCKET_C) * TEMP_BUCKET_C))
        base = baselines.get(key)
        if base is None:
            continue
        rows.append((comp, r[db._COL_SECTOR_NUMBER], r[db._COL_TYRE_AGE], r[db._COL_SECTOR_TIME_MS] - base))

    bins = _bin_age_residuals(rows)
    fitted: dict[tuple, dict[int, float]] = defaultdict(dict)   # (comp, sector) -> {age: offset}
    for (comp, sector, age), resid in bins.items():
        filt = _mad_filter([int(v) for v in resid])
        if len(filt) < MIN_AGE_BIN_SAMPLES:
            continue
        arr = np.array(filt, dtype=float)
        db.upsert_tyre_age_offset(conn, track_id, comp, regime, sector, age,
                                  float(arr.mean()), float(arr.std()), len(filt), 0, now)
        fitted[(comp, sector)][age] = float(arr.mean())

    for (comp, sector), ages in fitted.items():
        for age, off in _interpolate_gaps(ages).items():
            db.upsert_tyre_age_offset(conn, track_id, comp, regime, sector, age, off, None, 0, 1, now)
        slope = _tail_slope(ages, WARMUP_END_AGE, _PRIOR.get(COMPOUND_KNOB_NAMES[comp], 30.0),
                            MAX_TYRE_DEG_MS_PER_LAP)
        db.insert_calibration_coefficient(
            conn, track_id, COMPOUND_KNOB_NAMES[comp], regime, sector, "tail_slope",
            slope, 0, now, sample_count=sum(1 for a in ages if a >= WARMUP_END_AGE))


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
        if compound not in VALID_VISUAL_COMPOUNDS:
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



def _pit_stop_losses(
    sectors: list[tuple], baselines: dict[tuple[int, int], float], ai_controlled: int
) -> list[float]:
    """Per-stop pit-lane time loss in ms.

    The pit time splits across the pit_status=1 entry sector (pit entry) and the
    following out-lap sector(s) that carry the box stop + pit exit — which the game
    flags pit_status=0. From each entry we therefore sum the excess over the
    normal-sector baseline of that sector plus following sectors (same car+session)
    while they stay clearly inflated (> PIT_INFLATION_FACTOR over baseline), so the
    full ~20 s loss is captured, not just the small entry-sector excess.

    `sectors` must be all race sectors for cars that pitted, ordered by
    session, car, lap, sector (see db._SELECT_PIT_STOP_SECTORS).
    """
    losses: list[float] = []
    n = len(sectors)
    i = 0
    while i < n:
        entry = sectors[i]
        if entry[db.PIT_COL_STATUS] != 1:
            i += 1
            continue
        loss = 0.0
        valid = True
        j = i
        while j < n and (j - i) < MAX_PIT_STOP_SECTORS:
            s = sectors[j]
            if (s[db.PIT_COL_CAR] != entry[db.PIT_COL_CAR]
                    or s[db.PIT_COL_SESSION] != entry[db.PIT_COL_SESSION]):
                break
            baseline = baselines.get((s[db.PIT_COL_SECTOR], ai_controlled))
            if baseline is None or baseline <= 0:
                valid = False
                break
            excess = s[db.PIT_COL_TIME] - baseline
            # Always include the entry sector; include following sectors only while
            # they remain clearly inflated (the box/exit sector), then stop.
            if j == i or excess > baseline * PIT_INFLATION_FACTOR:
                loss += excess
                j += 1
            else:
                break
        if valid and loss > 0:
            losses.append(loss)
        i = j if j > i else i + 1
    return losses
