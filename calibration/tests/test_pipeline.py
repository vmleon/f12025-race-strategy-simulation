import re
from datetime import datetime

import pytest

from calibration import db
from calibration.pipeline import (
    _group_pit_stops, _mad_filter,
    COMPOUND_KNOB_NAMES, MIN_TYRE_DEG_SAMPLES, MIN_FUEL_SAMPLES,
    MIN_PIT_STOP_SAMPLES,
)


class TestCompoundMapping:

    def test_compound_mapping(self):
        assert COMPOUND_KNOB_NAMES[16] == "tyre_deg_soft"
        assert COMPOUND_KNOB_NAMES[17] == "tyre_deg_medium"
        assert COMPOUND_KNOB_NAMES[18] == "tyre_deg_hard"
        assert len(COMPOUND_KNOB_NAMES) == 3


class TestConstants:

    def test_minimum_samples(self):
        # base_pace is no longer fitted under Option C (see pipeline.py).
        assert MIN_TYRE_DEG_SAMPLES == 10
        assert MIN_FUEL_SAMPLES == 5
        assert MIN_PIT_STOP_SAMPLES == 3


def _make_pit_sector(session_uid=1, car_index=0, lap_number=10, sector_number=2,
                     sector_time_ms=45000, pit_status=1, tyre_compound_actual=16,
                     ai_controlled=0):
    """Build a tuple matching the column order of _SELECT_PIT_STOP_SECTORS."""
    return (session_uid, car_index, lap_number, sector_number,
            sector_time_ms, pit_status, tyre_compound_actual, ai_controlled)


class TestGroupPitStops:

    def test_single_stop_two_sectors(self):
        sectors = [
            _make_pit_sector(lap_number=10, sector_number=2, pit_status=1),
            _make_pit_sector(lap_number=11, sector_number=0, pit_status=2),
        ]
        stops = _group_pit_stops(sectors)
        assert len(stops) == 1
        assert len(stops[0]) == 2

    def test_two_stops_same_car(self):
        sectors = [
            _make_pit_sector(lap_number=10, sector_number=2, pit_status=1),
            _make_pit_sector(lap_number=11, sector_number=0, pit_status=2),
            _make_pit_sector(lap_number=25, sector_number=2, pit_status=1),
            _make_pit_sector(lap_number=26, sector_number=0, pit_status=2),
        ]
        stops = _group_pit_stops(sectors)
        assert len(stops) == 2
        assert len(stops[0]) == 2
        assert len(stops[1]) == 2

    def test_different_cars(self):
        sectors = [
            _make_pit_sector(car_index=0, lap_number=10, pit_status=1),
            _make_pit_sector(car_index=0, lap_number=11, pit_status=2),
            _make_pit_sector(car_index=1, lap_number=12, pit_status=1),
            _make_pit_sector(car_index=1, lap_number=13, pit_status=2),
        ]
        stops = _group_pit_stops(sectors)
        assert len(stops) == 2

    def test_single_sector_stop(self):
        """A pit stop with only a pit_status=1 sector (no exit sector recorded)."""
        sectors = [
            _make_pit_sector(lap_number=10, pit_status=1),
        ]
        stops = _group_pit_stops(sectors)
        assert len(stops) == 1
        assert len(stops[0]) == 1

    def test_orphan_exit_sector_discarded(self):
        """A pit_status=2 without a preceding pit_status=1 starts no group."""
        sectors = [
            _make_pit_sector(car_index=0, lap_number=5, pit_status=2),
            _make_pit_sector(car_index=1, lap_number=10, pit_status=1),
        ]
        stops = _group_pit_stops(sectors)
        assert len(stops) == 1
        assert stops[0][0][db.PIT_COL_CAR] == 1

    def test_empty_input(self):
        assert _group_pit_stops([]) == []



class TestMadFilter:

    def test_passes_through_when_too_few(self):
        assert _mad_filter([1, 2]) == [1, 2]

    def test_keeps_consistent_laps(self):
        times = [80_000, 80_100, 80_200, 80_150, 80_050]
        assert _mad_filter(times) == times

    def test_drops_extreme_outlier(self):
        # One huge outlier among very tightly clustered laps should be dropped.
        times = [80_000, 80_000, 80_010, 80_010, 80_020, 130_000]
        filtered = _mad_filter(times)
        assert 130_000 not in filtered
        assert len(filtered) == 5

    def test_zero_mad_is_passthrough(self):
        # All identical times → MAD is 0; we can't filter, return as-is.
        times = [80_000, 80_000, 80_000, 80_000]
        assert _mad_filter(times) == times



class _FakeCursor:
    """Mimics python-oracledb's bind-checking contract.

    For positional (list/tuple) binds, oracledb counts every ``:N`` occurrence
    in the SQL as a distinct bind position — so a MERGE that reuses ``:1`` in
    several clauses needs as many values as occurrences. This is exactly what
    raised ``DPY-4009: 20 positional bind values are required but 10 were
    provided`` against the live DB. For named (dict) binds, repeats refer to the
    same name, so only the distinct names must be supplied.
    """

    def __init__(self, rows=None):
        self.executed = []
        self.rows = rows or []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def fetchall(self):
        return self.rows

    def execute(self, sql, params):
        placeholders = re.findall(r":(\w+)", sql)
        if isinstance(params, dict):
            missing = [p for p in placeholders if p not in params]
            if missing:
                raise AssertionError(f"missing named binds: {sorted(set(missing))}")
        else:
            if len(params) != len(placeholders):
                raise AssertionError(
                    f"{len(placeholders)} positional bind values are required "
                    f"but {len(params)} were provided"
                )
        self.executed.append((sql, params))


class _FakeConn:
    def __init__(self, rows=None):
        self.cursor_obj = _FakeCursor(rows)
        self.commit_count = 0

    def cursor(self):
        return self.cursor_obj

    def commit(self):
        self.commit_count += 1


class TestFuelEffectPerSector:

    def test_fits_one_slope_per_sector(self, monkeypatch):
        from datetime import datetime as _dt
        from calibration.pipeline import _fit_fuel_effect, MIN_FUEL_SAMPLES

        captured = []

        def fake_insert(conn, track_id, knob, regime, sector, method,
                        value, is_default, now):
            captured.append((knob, sector))

        monkeypatch.setattr(db, "insert_calibration_coefficient", fake_insert)

        def row(sector, fuel, time_ms):
            r = [0] * 21
            r[db._COL_SECTOR_NUMBER] = sector
            r[db._COL_SECTOR_TIME_MS] = time_ms
            r[db._COL_FUEL] = fuel
            return tuple(r)

        data = []
        for sector in (0, 1, 2):
            for i in range(MIN_FUEL_SAMPLES):
                data.append(row(sector, 60.0 - i * 5, 30_000 + i * 10))

        _fit_fuel_effect(None, data, track_id=4, regime="PLAYER", now=_dt.now())

        sectors = sorted(s for (knob, s) in captured if knob == "fuel_effect")
        assert sectors == [0, 1, 2]

    def test_skips_sector_below_min_samples(self, monkeypatch):
        from datetime import datetime as _dt
        from calibration.pipeline import _fit_fuel_effect, MIN_FUEL_SAMPLES

        captured = []
        monkeypatch.setattr(
            db, "insert_calibration_coefficient",
            lambda *a, **k: captured.append((a[3], a[5])),  # length-only guard
        )

        def row(sector, fuel, time_ms):
            r = [0] * 21
            r[db._COL_SECTOR_NUMBER] = sector
            r[db._COL_SECTOR_TIME_MS] = time_ms
            r[db._COL_FUEL] = fuel
            return tuple(r)

        # Only sector 1 has enough samples; sectors 0 and 2 have one each.
        data = [row(0, 50.0, 30_000), row(2, 50.0, 30_000)]
        for i in range(MIN_FUEL_SAMPLES):
            data.append(row(1, 60.0 - i * 5, 30_000 + i * 10))

        _fit_fuel_effect(None, data, track_id=4, regime="AI", now=_dt.now())
        assert len(captured) == 1



class TestFitSlopeClamping:
    """Thin/early FP data can produce absurd or negative fitted slopes; those must
    fall back to the cold-start prior rather than poison the simulator."""

    def _deg_rows(self, compound, slope, n=12, base=24_000.0):
        rows = []
        for age in range(n):
            r = [0] * 21
            r[db._COL_TYRE_COMPOUND] = compound
            r[db._COL_SECTOR_NUMBER] = 1
            r[db._COL_TYRE_AGE] = age
            r[db._COL_SECTOR_TIME_MS] = base + slope * age
            rows.append(tuple(r))
        return rows

    def _run_deg(self, monkeypatch, rows):
        from datetime import datetime as _dt
        from calibration.pipeline import _fit_tyre_degradation
        captured = []
        monkeypatch.setattr(
            db, "insert_calibration_coefficient",
            lambda conn, t, knob, reg, sec, m, val, isd, now: captured.append((knob, val)))
        _fit_tyre_degradation(None, rows, track_id=4, regime="PLAYER", now=_dt.now())
        return captured

    def test_absurd_deg_slope_falls_back_to_prior(self, monkeypatch):
        # ~2000 ms/lap (≈6 s/lap) is implausible — fall back to the soft prior (50).
        captured = self._run_deg(monkeypatch, self._deg_rows(16, slope=2000.0))
        assert captured and captured[0][1] == pytest.approx(50.0)

    def test_negative_deg_slope_falls_back_to_prior(self, monkeypatch):
        # A tyre can't get faster with age at constant fuel — fall back (medium=30).
        captured = self._run_deg(monkeypatch, self._deg_rows(17, slope=-120.0))
        assert captured and captured[0][1] == pytest.approx(30.0)

    def test_plausible_deg_slope_is_kept(self, monkeypatch):
        # 35 ms/lap is within bounds and must be kept (hard prior would be 20).
        captured = self._run_deg(monkeypatch, self._deg_rows(18, slope=35.0))
        assert captured and captured[0][1] == pytest.approx(35.0, abs=1.0)

    def test_negative_fuel_slope_falls_back_to_prior(self, monkeypatch):
        from datetime import datetime as _dt
        from calibration.pipeline import _fit_fuel_effect
        captured = []
        monkeypatch.setattr(
            db, "insert_calibration_coefficient",
            lambda conn, t, knob, reg, sec, m, val, isd, now: captured.append((knob, val)))

        rows = []
        for i in range(8):
            r = [0] * 21
            r[db._COL_SECTOR_NUMBER] = 0
            r[db._COL_FUEL] = 100.0 - i * 5
            r[db._COL_SECTOR_TIME_MS] = 24_000.0 + i * 80  # time rises as fuel falls -> negative slope
            rows.append(tuple(r))
        _fit_fuel_effect(None, rows, track_id=4, regime="PLAYER", now=_dt.now())
        assert captured and captured[0][1] == pytest.approx(10.0)  # fuel prior


class TestSummarizeBucket:

    def test_mean_stddev_perfect(self):
        from calibration.pipeline import _summarize_bucket
        m, sd, perfect = _summarize_bucket([80_000, 80_100, 80_200])
        assert m == 80_100.0
        assert perfect == 80_000.0
        assert 80.0 < sd < 84.0  # population stddev of {0,100,200} ≈ 81.65

    def test_single_value(self):
        from calibration.pipeline import _summarize_bucket
        m, sd, perfect = _summarize_bucket([79_500])
        assert m == 79_500.0
        assert perfect == 79_500.0
        assert sd == 0.0


class TestSectorBaselineBucketing:

    def _row(self, sector=0, compound=16, ai=0, fuel=50.0, weather=0,
             temp=25, time_ms=30_000):
        # Matches the SELECT order of _SELECT_SECTOR_BASELINE_DATA.
        return (sector, compound, ai, fuel, weather, temp, time_ms)

    def test_skips_rows_with_missing_context(self):
        from calibration.pipeline import _bucket_sector_rows
        rows = [
            self._row(fuel=None),
            self._row(weather=None),
            self._row(temp=None),
            self._row(ai=None),
        ]
        assert _bucket_sector_rows(rows) == {}

    def test_splits_by_sector(self):
        from calibration.pipeline import _bucket_sector_rows
        rows = [self._row(sector=0), self._row(sector=1), self._row(sector=2)]
        groups = _bucket_sector_rows(rows)
        sectors = sorted(key[0] for key in groups)
        assert sectors == [0, 1, 2]

    def test_regime_split(self):
        from calibration.pipeline import _bucket_sector_rows
        rows = [self._row(ai=1, time_ms=31_000), self._row(ai=0, time_ms=30_000)]
        groups = _bucket_sector_rows(rows)
        regimes = {key[2] for key in groups}
        assert regimes == {"AI", "PLAYER"}

    def test_buckets_fuel_and_temp(self):
        from calibration.pipeline import _bucket_sector_rows
        # fuel 9→0 bucket, 14→20 bucket → two distinct groups
        rows = [self._row(fuel=9.0), self._row(fuel=14.0)]
        assert len(_bucket_sector_rows(rows)) == 2

    def test_excludes_out_of_range_compounds(self):
        from calibration.pipeline import _bucket_sector_rows
        # 0 = garbage; 19/20 = C-codes from tyre_compound_actual that violate
        # CHK_SECTOR_BASELINE_COMPOUND. Only visual codes (7,8,16,17,18) survive.
        rows = [self._row(compound=0), self._row(compound=19),
                self._row(compound=20), self._row(compound=16)]
        groups = _bucket_sector_rows(rows)
        assert {key[1] for key in groups} == {16}


class TestSectorBaselineConstants:

    def test_minimum_samples_is_three(self):
        from calibration.pipeline import MIN_SECTOR_BASELINE_SAMPLES
        assert MIN_SECTOR_BASELINE_SAMPLES == 3


class TestUpsertSectorBaseline:

    def test_binds_satisfy_merge_placeholders(self):
        from calibration.pipeline import _upsert_sector_baseline
        conn = _FakeConn()
        _upsert_sector_baseline(
            conn, track_id=4, sector_number=1, compound=16, regime="PLAYER",
            fuel_bucket_kg=40, weather=0, track_temp_bucket_c=30,
            mean_sector_ms=27_000.0, stddev_sector_ms=80.0,
            perfect_sector_ms=26_800.0, sample_count=7,
            fitted_at=datetime(2026, 6, 4, 12, 0, 0),
        )
        assert len(conn.cursor_obj.executed) == 1

    def test_does_not_commit_per_bucket(self):
        # The caller (_fit_sector_baselines) owns the commit; a per-bucket commit
        # would persist a half-finished fit if a later bucket raises.
        from calibration.pipeline import _upsert_sector_baseline
        conn = _FakeConn()
        _upsert_sector_baseline(
            conn, track_id=4, sector_number=1, compound=16, regime="PLAYER",
            fuel_bucket_kg=40, weather=0, track_temp_bucket_c=30,
            mean_sector_ms=27_000.0, stddev_sector_ms=80.0,
            perfect_sector_ms=26_800.0, sample_count=7,
            fitted_at=datetime(2026, 6, 4, 12, 0, 0),
        )
        assert conn.commit_count == 0


class TestTyreWearRateFit:

    def _row(self, compound, age, fl, fr, rl, rr):
        r = [0] * 33
        r[db._COL_TYRE_COMPOUND] = compound
        r[db._COL_TYRE_AGE] = age
        r[db._COL_WEAR_FL] = fl
        r[db._COL_WEAR_FR] = fr
        r[db._COL_WEAR_RL] = rl
        r[db._COL_WEAR_RR] = rr
        return tuple(r)

    def test_most_worn_picks_max_ignoring_nulls(self):
        from calibration.pipeline import _most_worn
        assert _most_worn(self._row(16, 0, 10.0, 20.0, 5.0, 8.0)) == 20.0
        assert _most_worn(self._row(16, 0, None, None, 12.0, None)) == 12.0
        assert _most_worn(self._row(16, 0, None, None, None, None)) is None

    def test_fits_wear_rate_on_most_worn_wheel(self, monkeypatch):
        from calibration.pipeline import _fit_tyre_wear_rate, MIN_WEAR_SAMPLES
        captured = []
        monkeypatch.setattr(
            db, "insert_calibration_coefficient",
            lambda c, t, knob, reg, sec, method, val, d, now:
                captured.append((knob, sec, val)),
        )
        # Most-worn wheel (RL) climbs 2%/lap; others slower.
        data = [self._row(16, age, age, age, age * 2.0, age * 1.5)
                for age in range(MIN_WEAR_SAMPLES + 3)]
        _fit_tyre_wear_rate(None, data, track_id=4, regime="AI", now=datetime.now())

        assert len(captured) == 1
        knob, sec, val = captured[0]
        assert knob == "tyre_wear_rate_soft"
        assert sec is None  # sector-wide
        assert val == pytest.approx(2.0, abs=0.01)

    def test_skips_below_min_samples(self, monkeypatch):
        from calibration.pipeline import _fit_tyre_wear_rate
        captured = []
        monkeypatch.setattr(
            db, "insert_calibration_coefficient",
            lambda *a, **k: captured.append(a),
        )
        data = [self._row(17, age, age, age, age, age) for age in range(3)]
        _fit_tyre_wear_rate(None, data, track_id=4, regime="AI", now=datetime.now())
        assert captured == []

    def test_clamps_negative_slope_to_zero(self, monkeypatch):
        from calibration.pipeline import _fit_tyre_wear_rate, MIN_WEAR_SAMPLES
        captured = []
        monkeypatch.setattr(
            db, "insert_calibration_coefficient",
            lambda c, t, knob, reg, sec, method, val, d, now: captured.append(val),
        )
        # Wear that decreases with age (noise) -> negative slope -> clamp to 0.
        data = [self._row(18, age, 50 - age, 50 - age, 50 - age, 50 - age)
                for age in range(MIN_WEAR_SAMPLES + 2)]
        _fit_tyre_wear_rate(None, data, track_id=4, regime="AI", now=datetime.now())
        assert captured == [0.0]


class TestFitSectorBaselinesAtomicity:

    def test_commits_once_for_all_buckets(self):
        # Two buckets (compound 16 and 17) → two upserts, but a single commit at
        # the end so any failure mid-loop rolls back cleanly.
        from calibration.pipeline import _fit_sector_baselines
        rows = (
            [(0, 16, 0, 50.0, 0, 25, 30_000 + i) for i in range(3)]
            + [(0, 17, 0, 50.0, 0, 25, 31_000 + i) for i in range(3)]
        )
        conn = _FakeConn(rows)
        _fit_sector_baselines(conn, track_id=4)
        assert conn.commit_count == 1
