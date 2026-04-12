from __future__ import annotations

METHOD_NAME = "cold_start_default"

KNOB_DEFAULTS: list[tuple[str, float]] = [
    ("tyre_deg_soft",        0.05),
    ("tyre_deg_medium",      0.03),
    ("tyre_deg_hard",        0.02),
    ("fuel_effect",          0.01),
    ("front_wing_damage",    0.02),
    ("floor_damage",         0.04),
    ("engine_damage",        0.01),
    ("dirty_air",            0.30),
    ("drs_advantage",       -0.20),
    ("overtake_probability", 0.15),
    ("safety_car_rate",      0.01),
    ("pit_stop_time_loss",  3000),
]

REGIMES = ["PLAYER", "AI"]
