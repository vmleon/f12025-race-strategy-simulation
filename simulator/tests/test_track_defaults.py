from simulator.car_state import _pace_from_recent_laps
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


class TestPaceFromRecentLaps:
    def test_uses_circuit_default_when_no_observed_laps(self):
        # Monaco — fallback should be the Monaco default, not 90 s.
        pace = _pace_from_recent_laps([], track_id=5)
        assert pace == circuit_default_ms(5)
        assert pace < 80_000

    def test_uses_median_of_observed_when_available(self):
        # Three laps: 80, 81, 82. Median index = 1 → 81 (sorted upper-median).
        pace = _pace_from_recent_laps([80_000, 81_000, 82_000], track_id=10)
        assert pace == 81_000.0

    def test_observed_overrides_circuit_default(self):
        # Even on a slow circuit, observed fast laps win.
        pace_obs = _pace_from_recent_laps([70_000], track_id=10)  # Spa
        assert pace_obs == 70_000.0
        # Compared with the empty case where Spa's default kicks in.
        pace_def = _pace_from_recent_laps([], track_id=10)
        assert pace_def > 100_000

    def test_unknown_track_falls_back_to_legacy_90s(self):
        pace = _pace_from_recent_laps([], track_id=999)
        assert pace == DEFAULT_LAP_MS == 90_000.0


class TestPaceFromBaseline:
    def test_baseline_used_when_no_observed_laps(self):
        # No observed laps but a calibrated baseline — use the baseline.
        pace = _pace_from_recent_laps([], track_id=5, baseline_lap_ms=78_500)
        assert pace == 78_500.0

    def test_observed_overrides_baseline(self):
        # Even when a baseline is set, an observed lap wins.
        pace = _pace_from_recent_laps([80_000], track_id=5, baseline_lap_ms=78_500)
        assert pace == 80_000.0

    def test_circuit_default_when_neither_observed_nor_baseline(self):
        # No observed laps and no baseline — fall through to circuit default.
        pace = _pace_from_recent_laps([], track_id=5, baseline_lap_ms=0)
        assert pace == circuit_default_ms(5)

    def test_baseline_used_for_unknown_track(self):
        # Unknown track + baseline ⇒ baseline still wins over the 90 s default.
        pace = _pace_from_recent_laps([], track_id=999, baseline_lap_ms=82_000)
        assert pace == 82_000.0
