from simulator.coefficients import Coefficients


def test_player_falls_back_to_ai_when_missing():
    c = Coefficients()
    c.put("tyre_deg_soft", "AI", -1, 0.05)
    # No PLAYER value set
    assert c.get("tyre_deg_soft", "PLAYER") == 0.05


def test_player_uses_own_value_when_present():
    c = Coefficients()
    c.put("tyre_deg_soft", "AI", -1, 0.05)
    c.put("tyre_deg_soft", "PLAYER", -1, 0.08)
    assert c.get("tyre_deg_soft", "PLAYER") == 0.08


def test_ai_returns_zero_when_missing():
    c = Coefficients()
    assert c.get("nonexistent", "AI") == 0.0


def test_sector_fallback_still_works():
    c = Coefficients()
    c.put("dirty_air", "PLAYER", -1, 0.30)
    # Sector-specific lookup falls back to sector -1
    assert c.get("dirty_air", "PLAYER", 2) == 0.30


def test_player_sector_fallback_then_ai():
    c = Coefficients()
    c.put("fuel_effect", "AI", -1, 0.01)
    # PLAYER has no value at all — should fall back through sector -1, then AI
    assert c.get("fuel_effect", "PLAYER", 2) == 0.01


def test_defaults_have_both_regimes():
    c = Coefficients.defaults()
    assert c.get("pit_stop_time_loss", "PLAYER") > 0
    assert c.get("pit_stop_time_loss", "AI") > 0
