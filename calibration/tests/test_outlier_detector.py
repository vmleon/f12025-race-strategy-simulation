from calibration.outlier_detector import (
    SectorEntry, SectorKey, DriverRating,
    detect_outliers, _index_ratings, _lookup_skill_rating, _percentile, _median,
)


def _entry(session_uid, car_index, lap, sector, time_ms, driver, track_id, compound, ai):
    return SectorEntry(session_uid, car_index, lap, sector, time_ms, driver, track_id, compound, ai)


class TestIqrDetection:

    def test_flags_obvious_outlier_with_ten_samples(self):
        times = [29500, 29800, 30000, 30100, 30200, 30300, 30400, 30500, 30600, 45000]
        entries = [_entry(1, 0, i + 2, 1, t, "Hamilton", 1, 16, False) for i, t in enumerate(times)]

        outliers = detect_outliers(entries, [])

        assert len(outliers) == 1
        flagged_laps = {o.lap_number for o in outliers}
        # lap index for 45000 is i=9 → lap_number=11
        assert 11 in flagged_laps

    def test_zero_variance_produces_no_outliers(self):
        entries = [_entry(1, 0, i + 2, 1, 30000, "Verstappen", 1, 16, False) for i in range(12)]
        outliers = detect_outliers(entries, [])
        assert len(outliers) == 0

    def test_ai_multiplier_is_tighter_than_human(self):
        times = [29500, 29800, 30000, 30100, 30200, 30300, 30400, 30500, 30600, 33000]
        ai_entries = [_entry(1, 0, i + 2, 1, t, "AI_Driver", 1, 16, True) for i, t in enumerate(times)]
        human_entries = [_entry(1, 1, i + 2, 1, t, "Human_Driver", 1, 16, False) for i, t in enumerate(times)]

        ai_outliers = detect_outliers(ai_entries, [])
        human_outliers = detect_outliers(human_entries, [])

        assert len(ai_outliers) >= len(human_outliers)


class TestColdStartDetection:

    def test_cold_start_uses_skill_rating(self):
        entries = []
        # 15 entries from other drivers to establish cross-driver median ~30000
        for i in range(15):
            entries.append(_entry(1, i + 1, 2, 1, 29800 + i * 30, f"OtherDriver{i}", 1, 16, False))
        # Target driver: 5 entries, last one above tolerance
        entries.append(_entry(1, 0, 2, 1, 30000, "TestDriver", 1, 16, False))
        entries.append(_entry(1, 0, 3, 1, 30100, "TestDriver", 1, 16, False))
        entries.append(_entry(1, 0, 4, 1, 30200, "TestDriver", 1, 16, False))
        entries.append(_entry(1, 0, 5, 1, 30300, "TestDriver", 1, 16, False))
        entries.append(_entry(1, 0, 6, 1, 32000, "TestDriver", 1, 16, False))

        ratings = [DriverRating("TestDriver", -1, 80)]
        outliers = detect_outliers(entries, ratings)

        # skill_rating=80: tolerance = 1500*(110-80)/60 = 750ms
        # 32000 > median(~30000) + 750 → flagged
        assert any(o.car_index == 0 and o.lap_number == 6 for o in outliers)

    def test_default_rating_when_no_rating_exists(self):
        entries = []
        for i in range(15):
            entries.append(_entry(1, i + 1, 2, 1, 30000, f"Other{i}", 1, 16, False))
        # Default skill=50: tolerance = 1500*(110-50)/60 = 1500ms
        entries.append(_entry(1, 0, 2, 1, 30000, "Norris", 1, 16, False))
        entries.append(_entry(1, 0, 3, 1, 30100, "Norris", 1, 16, False))
        entries.append(_entry(1, 0, 4, 1, 31600, "Norris", 1, 16, False))

        outliers = detect_outliers(entries, [])

        assert any(o.car_index == 0 and o.lap_number == 4 for o in outliers)

    def test_track_specific_rating_overrides_global(self):
        ratings = [
            DriverRating("Driver1", -1, 50),
            DriverRating("Driver1", 5, 95),
        ]
        index = _index_ratings(ratings)
        assert _lookup_skill_rating(index, "Driver1", 5) == 95


class TestPercentileMedian:

    def test_percentile_computation(self):
        values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
        assert _percentile(values, 25) == 3
        assert _percentile(values, 75) == 8

    def test_median_of_even_count_interpolates(self):
        values = [10, 20, 30, 40]
        assert _median(values) == 25

    def test_empty_input_returns_no_outliers(self):
        assert detect_outliers([], []) == []
