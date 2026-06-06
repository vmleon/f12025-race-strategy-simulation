from simulator.coefficients import Coefficients
from simulator.tyre_lifespan import (
    compound_lifespan,
    laps_to_cliff,
    stint_cap_laps,
)


class TestLapsToCliff:
    def test_derives_from_wear_rate(self):
        # 40% cliff / 2%-per-lap = 20 laps.
        assert laps_to_cliff(16, 2.0) == 20

    def test_falls_back_to_lifespan_when_uncalibrated(self):
        assert laps_to_cliff(16, 0.0) == compound_lifespan(16)
        assert laps_to_cliff(16, -1.0) == compound_lifespan(16)

    def test_never_below_one_lap(self):
        assert laps_to_cliff(16, 1000.0) == 1


class TestStintCap:
    def test_uses_calibrated_wear_rate(self):
        c = Coefficients()
        c.put("tyre_wear_rate_soft", "AI", -1, 4.0)  # heavy wear
        assert stint_cap_laps(16, c, "AI") == 10  # 40 / 4

    def test_falls_back_without_coefficients(self):
        assert stint_cap_laps(16, None, "AI") == compound_lifespan(16)

    def test_falls_back_for_compound_without_wear_knob(self):
        # Intermediates have no wear-rate knob -> hardcoded lifespan.
        c = Coefficients.defaults()
        assert stint_cap_laps(7, c, "AI") == compound_lifespan(7)

    def test_defaults_reproduce_old_lifespans(self):
        # Wear-rate defaults are tuned so the 40% cliff ≈ the old hardcoded caps.
        c = Coefficients.defaults()
        assert stint_cap_laps(16, c, "AI") == 30
        assert stint_cap_laps(17, c, "AI") == 37
        assert stint_cap_laps(18, c, "AI") == 45
