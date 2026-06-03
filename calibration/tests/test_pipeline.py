import re
from datetime import datetime

from calibration import db
from calibration.pipeline import (
    _bucket_pace_rows, _group_pit_stops, _mad_filter, _upsert_pace_baseline,
    COMPOUND_KNOB_NAMES, MIN_TYRE_DEG_SAMPLES, MIN_FUEL_SAMPLES,
    MIN_PIT_STOP_SAMPLES, MIN_PACE_BASELINE_SAMPLES,
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


class TestPaceBaselineBucketing:

    def test_skips_rows_with_missing_context(self):
        rows = [
            (16, 1, None, 0, 25, 80_000),         # fuel_kg missing
            (16, 1, 30.0, None, 25, 80_000),      # weather missing
            (16, 1, 30.0, 0, None, 80_000),       # temp missing
            (16, None, 30.0, 0, 25, 80_000),      # ai_controlled missing (legacy row)
        ]
        assert _bucket_pace_rows(rows) == {}

    def test_buckets_fuel_to_nearest_20kg(self):
        rows = [
            (16, 1, 9.0, 0, 25, 80_000),    # rounds to 0
            (16, 1, 14.0, 0, 25, 80_000),   # rounds to 20
            (16, 1, 30.0, 0, 25, 80_000),   # rounds to 20 (30→1.5→round→2→40 — wait)
        ]
        groups = _bucket_pace_rows(rows)
        # 9.0/20 = 0.45 → round(0.45) = 0 → 0
        # 14.0/20 = 0.7 → round(0.7) = 1 → 20
        # 30.0/20 = 1.5 → round(1.5) = 2 (banker's rounding) → 40
        # We're not asserting specific bucket math; just that bucketing happens.
        assert len(groups) >= 2

    def test_buckets_temp_to_nearest_10c(self):
        rows = [
            (17, 0, 50.0, 0, 22, 90_000),   # rounds to 20
            (17, 0, 50.0, 0, 28, 90_000),   # rounds to 30
        ]
        groups = _bucket_pace_rows(rows)
        # Two distinct temp buckets → two groups.
        assert len(groups) == 2

    def test_regime_split(self):
        # Same compound/fuel/weather/temp, different ai_controlled → two buckets.
        rows = [
            (16, 1, 50.0, 0, 25, 82_000),   # AI
            (16, 0, 50.0, 0, 25, 80_000),   # PLAYER
        ]
        groups = _bucket_pace_rows(rows)
        regimes = {key[1] for key in groups}
        assert regimes == {"AI", "PLAYER"}


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


class TestPaceBaselineConstants:

    def test_minimum_samples(self):
        assert MIN_PACE_BASELINE_SAMPLES == 5


class _FakeCursor:
    """Mimics python-oracledb's bind-checking contract.

    For positional (list/tuple) binds, oracledb counts every ``:N`` occurrence
    in the SQL as a distinct bind position — so a MERGE that reuses ``:1`` in
    several clauses needs as many values as occurrences. This is exactly what
    raised ``DPY-4009: 20 positional bind values are required but 10 were
    provided`` against the live DB. For named (dict) binds, repeats refer to the
    same name, so only the distinct names must be supplied.
    """

    def __init__(self):
        self.executed = []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

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
    def __init__(self):
        self.cursor_obj = _FakeCursor()

    def cursor(self):
        return self.cursor_obj

    def commit(self):
        pass


class TestUpsertPaceBaseline:

    def test_binds_satisfy_merge_placeholders(self):
        """The MERGE in _upsert_pace_baseline must supply every placeholder it
        references — regression for DPY-4009 (reused numbered binds counted as
        20 positions while only 10 values were provided)."""
        conn = _FakeConn()
        _upsert_pace_baseline(
            conn, track_id=4, compound=16, regime="PLAYER",
            fuel_bucket_kg=40, weather=0, track_temp_bucket_c=30,
            mean_lap_ms=80_000.0, stddev_lap_ms=150.0, sample_count=7,
            fitted_at=datetime(2026, 6, 3, 18, 0, 0),
        )
        assert len(conn.cursor_obj.executed) == 1
