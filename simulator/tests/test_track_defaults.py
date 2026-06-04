from simulator.car_state import _sector_pace_ms, _perfect_sector_floor_ms
from simulator.track_defaults import DEFAULT_LAP_MS, circuit_default_ms


class TestCircuitDefaults:
    def test_known_short_circuit_is_faster_than_default(self):
        # Monaco — the slowest fastest-lap should still be well under 90 s.
        assert circuit_default_ms(5) < 80_000

    def test_known_long_circuit_is_slower_than_default(self):
        # Spa — the longest active F1 circuit should be well over 90 s.
        assert circuit_default_ms(10) > 100_000

    def test_unknown_track_falls_back_to_global_default(self):
        assert circuit_default_ms(999) == DEFAULT_LAP_MS

    def test_every_documented_track_id_present(self):
        # GameMappings TRACKS has 0..33 — all should have a default.
        for track_id in range(34):
            value = circuit_default_ms(track_id)
            assert 40_000 < value < 120_000, (
                f"track {track_id} default {value} is outside plausible F1 range"
            )

    def test_relative_ordering(self):
        # Sanity: Monaco (slow corner-heavy) faster than Spa (long, fast).
        assert circuit_default_ms(5) < circuit_default_ms(10)
        # Bahrain Outer (short layout) faster than full Bahrain.
        assert circuit_default_ms(21) < circuit_default_ms(3)


class TestSectorPace:
    def test_circuit_default_split_when_empty(self):
        from simulator.car_state import _sector_pace_ms
        pace = _sector_pace_ms([[], [], []], [0, 0, 0], track_id=5)
        third = circuit_default_ms(5) / 3.0
        assert pace == [third, third, third]

    def test_observed_median_per_sector(self):
        from simulator.car_state import _sector_pace_ms
        pace = _sector_pace_ms(
            [[25_000, 25_200, 24_800], [26_000], []], [0, 0, 0], track_id=10)
        assert pace[0] == 25_000.0          # median of the three
        assert pace[1] == 26_000.0          # single value
        assert pace[2] == circuit_default_ms(10) / 3.0  # empty → default split

    def test_baseline_when_no_observed(self):
        from simulator.car_state import _sector_pace_ms
        pace = _sector_pace_ms([[], [], []], [24_000, 25_000, 26_000], track_id=5)
        assert pace == [24_000.0, 25_000.0, 26_000.0]

    def test_observed_overrides_baseline(self):
        from simulator.car_state import _sector_pace_ms
        pace = _sector_pace_ms([[23_500], [], []], [24_000, 25_000, 26_000], track_id=5)
        assert pace[0] == 23_500.0
        assert pace[1] == 25_000.0


class TestPerfectSectorFloor:
    def test_uses_calibrated_perfect_when_present(self):
        from simulator.car_state import _perfect_sector_floor_ms
        floor = _perfect_sector_floor_ms([24_000, 25_000, 0], [25_000.0, 26_000.0, 27_000.0])
        assert floor[0] == 24_000.0
        assert floor[1] == 25_000.0
        assert floor[2] == 27_000.0 * 0.9   # missing → 90% of base

    def test_all_missing_falls_back_to_90pct(self):
        from simulator.car_state import _perfect_sector_floor_ms
        floor = _perfect_sector_floor_ms([0, 0, 0], [30_000.0, 30_000.0, 30_000.0])
        assert floor == [27_000.0, 27_000.0, 27_000.0]
