import numpy as np

from calibration.pipeline import _bin_age_residuals, _interpolate_gaps, _tail_slope


def test_bin_age_residuals_groups_by_compound_sector_age_and_skips_age0():
    rows = [(18, 1, 0, 999.0), (18, 1, 1, 80.0), (18, 1, 1, 100.0), (18, 1, 2, -40.0)]
    bins = _bin_age_residuals(rows)
    assert (18, 1, 0) not in bins
    assert bins[(18, 1, 1)] == [80.0, 100.0]
    assert bins[(18, 1, 2)] == [-40.0]


def test_interpolate_gaps_fills_interior_ages_linearly():
    ages = {1: 90.0, 4: 0.0}
    filled = _interpolate_gaps(ages)
    assert filled[2] == 60.0
    assert filled[3] == 30.0


def test_tail_slope_is_nonnegative_and_from_late_bins():
    ages = {1: 80.0, 2: 0.0, 3: -10.0, 4: 0.0, 5: 20.0}
    slope = _tail_slope(ages, warmup_end=3, prior=20.0, max_slope=300.0)
    assert slope > 0.0


def test_tail_slope_falls_back_to_prior_when_too_few_late_bins():
    ages = {1: 80.0, 2: 0.0}
    assert _tail_slope(ages, warmup_end=3, prior=20.0, max_slope=300.0) == 20.0
