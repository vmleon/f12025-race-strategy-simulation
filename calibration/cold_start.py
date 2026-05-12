from __future__ import annotations

METHOD_NAME = "cold_start_default"

# Only knobs the simulator engine actually consumes under Option C.
# Damage / dirty-air / DRS / overtake / safety-car / base_pace knobs were
# dropped in the Option C simplification.
KNOB_DEFAULTS: list[tuple[str, float]] = [
    ("tyre_deg_soft",       0.05),
    ("tyre_deg_medium",     0.03),
    ("tyre_deg_hard",       0.02),
    ("fuel_effect",         0.01),
    ("pit_stop_time_loss", 22000),
]

REGIMES = ["PLAYER", "AI"]
