from calibration import db
from calibration.pipeline import (
    _compute_settings_hash, _group_by_sector, _has_no_damage,
    COMPOUND_KNOB_NAMES, MIN_BASE_PACE_SAMPLES, MIN_TYRE_DEG_SAMPLES, MIN_FUEL_SAMPLES,
    MAX_TYRE_AGE_CLEAN, MIN_GAP_CLEAN_AIR_MS,
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

    def test_has_no_damage_clean(self):
        row = _make_row()
        assert _has_no_damage(row) is True

    def test_has_no_damage_with_floor_damage(self):
        row = _make_row(floor_damage=15)
        assert _has_no_damage(row) is False

    def test_group_by_sector(self):
        data = [_make_row(sector_number=0), _make_row(sector_number=1), _make_row(sector_number=0)]
        groups = _group_by_sector(data)
        assert len(groups[0]) == 2
        assert len(groups[1]) == 1

    def test_settings_hash_deterministic(self):
        row1 = _make_row()
        row2 = _make_row(sector_number=1, sector_time_ms=31000)
        assert _compute_settings_hash(row1) == _compute_settings_hash(row2)

    def test_settings_hash_differs_for_different_settings(self):
        row1 = _make_row()
        row2 = _make_row(ai_difficulty=99)
        assert _compute_settings_hash(row1) != _compute_settings_hash(row2)


class TestCleanConditionsFiltering:

    def test_clean_conditions_filter(self):
        data = []
        # 6 clean rows (tyre age 0..5, all <= MAX_TYRE_AGE_CLEAN=5)
        for i in range(6):
            data.append(_make_row(sector_number=0, sector_time_ms=30000 + i * 10, tyre_age_laps=i))
        # 3 dirty rows (high tyre age)
        for i in range(3):
            data.append(_make_row(sector_number=0, sector_time_ms=32000, tyre_age_laps=20))

        by_sector = _group_by_sector(data)
        sector_data = by_sector[0]
        clean = [r for r in sector_data
                 if r[db._COL_TYRE_AGE] <= MAX_TYRE_AGE_CLEAN
                 and (r[db._COL_GAP_AHEAD] > MIN_GAP_CLEAN_AIR_MS or r[db._COL_GAP_AHEAD] == 0)
                 and _has_no_damage(r)]

        assert len(clean) == 6


class TestCompoundMapping:

    def test_compound_mapping(self):
        assert COMPOUND_KNOB_NAMES[16] == "tyre_deg_soft"
        assert COMPOUND_KNOB_NAMES[17] == "tyre_deg_medium"
        assert COMPOUND_KNOB_NAMES[18] == "tyre_deg_hard"
        assert len(COMPOUND_KNOB_NAMES) == 3


class TestConstants:

    def test_minimum_samples(self):
        assert MIN_BASE_PACE_SAMPLES == 5
        assert MIN_TYRE_DEG_SAMPLES == 10
        assert MIN_FUEL_SAMPLES == 5
