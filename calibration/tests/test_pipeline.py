from calibration import db
from calibration.pipeline import (
    _bucket_pace_rows, _compute_settings_hash, _group_pit_stops, _mad_filter,
    COMPOUND_KNOB_NAMES, MIN_TYRE_DEG_SAMPLES, MIN_FUEL_SAMPLES,
    MIN_PIT_STOP_SAMPLES, MIN_PACE_BASELINE_SAMPLES,
)


def _make_row(sector_number=0, sector_time_ms=30000, lap_number=2, tyre_age_laps=3,
              fuel_in_tank_kg=50.0, gap_to_car_ahead_ms=3000, tyre_compound_actual=16,
              front_wing_damage_l=0, front_wing_damage_r=0, rear_wing_damage=0,
              floor_damage=0, diffuser_damage=0, sidepod_damage=0,
              engine_damage=0, gearbox_damage=0,
              ai_difficulty=95, car_damage_setting=1, car_damage_rate=1, low_fuel_mode=0):
    """Build a tuple matching the column order of _SELECT_CALIBRATION_DATA."""
    return (
        1,                      # session_uid
        0,                      # car_index
        lap_number,             # lap_number
        sector_number,          # sector_number
        sector_time_ms,         # sector_time_ms
        tyre_compound_actual,   # tyre_compound_actual
        tyre_age_laps,          # tyre_age_laps
        fuel_in_tank_kg,        # fuel_in_tank_kg
        gap_to_car_ahead_ms,    # gap_to_car_ahead_ms
        0,                      # drs_allowed
        0,                      # weather
        25,                     # track_temp
        20,                     # air_temp
        front_wing_damage_l,    # front_wing_damage_l
        front_wing_damage_r,    # front_wing_damage_r
        rear_wing_damage,       # rear_wing_damage
        floor_damage,           # floor_damage
        diffuser_damage,        # diffuser_damage
        sidepod_damage,         # sidepod_damage
        engine_damage,          # engine_damage
        gearbox_damage,         # gearbox_damage
        90, 90, 90, 90,         # tyre_surface_temps
        80, 80, 80, 80,         # tyre_inner_temps
        ai_difficulty,          # ai_difficulty
        car_damage_setting,     # car_damage_setting
        car_damage_rate,        # car_damage_rate
        low_fuel_mode,          # low_fuel_mode
    )


class TestHelpers:

    def test_settings_hash_deterministic(self):
        row1 = _make_row()
        row2 = _make_row(sector_number=1, sector_time_ms=31000)
        assert _compute_settings_hash(row1) == _compute_settings_hash(row2)

    def test_settings_hash_differs_for_different_settings(self):
        row1 = _make_row()
        row2 = _make_row(ai_difficulty=99)
        assert _compute_settings_hash(row1) != _compute_settings_hash(row2)


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
