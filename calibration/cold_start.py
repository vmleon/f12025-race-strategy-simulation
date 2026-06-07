from __future__ import annotations

METHOD_NAME = "cold_start_default"

# Only knobs the simulator engine actually consumes under Option C.
# Damage / dirty-air / DRS / overtake / safety-car / base_pace knobs were
# dropped in the Option C simplification.
# Units are milliseconds, matching the fitted coefficients and the engine, which
# adds these straight onto ms sector times. tyre_deg_* are ms/lap/sector
# (50 ms = 0.05 s); fuel_effect is ms/kg/sector (10 ms = 0.01 s). These doubled as
# the fallback prior when a fit is rejected as implausible (see pipeline._PRIOR).
KNOB_DEFAULTS: list[tuple[str, float]] = [
    ("tyre_deg_soft",       50.0),
    ("tyre_deg_medium",     30.0),
    ("tyre_deg_hard",       20.0),
    ("fuel_effect",         10.0),
    ("pit_stop_time_loss", 22000),
    # Wear-rate %/lap (most-worn wheel); laps-to-cliff at the 80% cliff ≈ S30/M37/H45.
    ("tyre_wear_rate_soft",   2.67),
    ("tyre_wear_rate_medium", 2.16),
    ("tyre_wear_rate_hard",   1.78),
]

REGIMES = ["PLAYER", "AI"]
