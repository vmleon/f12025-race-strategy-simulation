from simulator.tyre_curve import TyreCurves


def _curve():
    c = TyreCurves()
    for age, off in {1: 80.0, 2: -30.0, 4: -10.0}.items():   # gap at age 3
        c.put(18, "AI", 1, age, off)
    return c


def test_offset_exact_bin():
    assert _curve().offset(18, "AI", 1, 2) == -30.0


def test_offset_interpolates_interior_gap():
    assert _curve().offset(18, "AI", 1, 3) == -20.0   # midpoint of -30 and -10


def test_offset_clamps_below_first_bin_to_first_value():
    assert _curve().offset(18, "AI", 1, 0) == 80.0    # out-lap clamp


def test_offset_above_max_returns_none():
    assert _curve().offset(18, "AI", 1, 9) is None    # caller extrapolates


def test_player_falls_back_to_ai():
    assert _curve().offset(18, "PLAYER", 1, 2) == -30.0


def test_unknown_key_returns_none():
    assert _curve().offset(16, "AI", 1, 2) is None


def test_max_age():
    assert _curve().max_age(18, "AI", 1) == 4
    assert _curve().max_age(16, "AI", 1) is None
