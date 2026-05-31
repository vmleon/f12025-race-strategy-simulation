from calibration.outlier_detector import (
    SectorEntry,
    detect_outliers, _percentile, _median,
    weather_category, MIN_SAMPLES_FOR_IQR,
)


def _entry(session_uid, car_index, lap, sector, time_ms, driver, track_id, compound, ai, weather=0):
    return SectorEntry(session_uid, car_index, lap, sector, time_ms, driver, track_id, compound, ai, weather)


class TestIqrDetection:

    def test_flags_obvious_outlier_with_ten_samples(self):
        times = [29500, 29800, 30000, 30100, 30200, 30300, 30400, 30500, 30600, 45000]
        entries = [_entry(1, 0, i + 2, 1, t, "Hamilton", 1, 16, False) for i, t in enumerate(times)]

        outliers = detect_outliers(entries)

        assert len(outliers) == 1
        flagged_laps = {o.lap_number for o in outliers}
        # lap index for 45000 is i=9 → lap_number=11
        assert 11 in flagged_laps

    def test_zero_variance_produces_no_outliers(self):
        entries = [_entry(1, 0, i + 2, 1, 30000, "Verstappen", 1, 16, False) for i in range(12)]
        outliers = detect_outliers(entries)
        assert len(outliers) == 0

    def test_ai_multiplier_is_tighter_than_human(self):
        times = [29500, 29800, 30000, 30100, 30200, 30300, 30400, 30500, 30600, 33000]
        ai_entries = [_entry(1, 0, i + 2, 1, t, "AI_Driver", 1, 16, True) for i, t in enumerate(times)]
        human_entries = [_entry(1, 1, i + 2, 1, t, "Human_Driver", 1, 16, False) for i, t in enumerate(times)]

        ai_outliers = detect_outliers(ai_entries)
        human_outliers = detect_outliers(human_entries)

        assert len(ai_outliers) >= len(human_outliers)

    def test_undersized_group_flags_nothing(self):
        entries = [
            SectorEntry(session_uid=1, car_index=0, lap_number=lap, sector_number=0,
                        sector_time_ms=30000 + (9000 if lap == 3 else 0),
                        driver_name="X", track_id=1, tyre_compound_actual=16,
                        ai_controlled=False)
            for lap in range(1, 5)  # 4 samples < MIN_SAMPLES_FOR_IQR
        ]
        assert detect_outliers(entries) == []


class TestPercentileMedian:

    def test_percentile_computation(self):
        values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
        assert _percentile(values, 25) == 3
        assert _percentile(values, 75) == 8

    def test_median_of_even_count_interpolates(self):
        values = [10, 20, 30, 40]
        assert _median(values) == 25

    def test_empty_input_returns_no_outliers(self):
        assert detect_outliers([]) == []


class TestWeatherCategory:

    def test_dry_conditions(self):
        assert weather_category(0) == "dry"   # clear
        assert weather_category(1) == "dry"   # light cloud
        assert weather_category(2) == "dry"   # overcast

    def test_wet_conditions(self):
        assert weather_category(3) == "wet"   # light rain
        assert weather_category(4) == "wet"   # heavy rain
        assert weather_category(5) == "wet"   # storm


class TestWeatherGrouping:

    def test_wet_sector_not_outlier_against_dry_baseline(self):
        # 10 dry sectors ~30s
        dry = [_entry(1, 0, i + 2, 1, 30000 + i * 50, "Hamilton", 1, 16, False, weather=0)
               for i in range(10)]
        # 1 wet sector at 35s — would be an outlier if grouped with dry
        wet = [_entry(1, 0, 20, 1, 35000, "Hamilton", 1, 16, False, weather=4)]

        outliers = detect_outliers(dry + wet)

        # Wet sector should NOT be flagged — it's in its own group (too few samples → skipped)
        wet_flagged = [o for o in outliers if o.lap_number == 20]
        assert len(wet_flagged) == 0, "Wet sector should not be flagged as outlier against dry data"

    def test_same_weather_groups_together(self):
        # 10 dry entries with an obvious dry outlier
        times = [29500, 29800, 30000, 30100, 30200, 30300, 30400, 30500, 30600, 45000]
        entries = [_entry(1, 0, i + 2, 1, t, "Hamilton", 1, 16, False, weather=0)
                   for i, t in enumerate(times)]

        outliers = detect_outliers(entries)

        assert len(outliers) == 1
        assert outliers[0].lap_number == 11  # the 45000ms entry
