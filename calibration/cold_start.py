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
    # Wear-rate %/lap (most-worn wheel); laps-to-cliff at 40% ≈ old lifespans.
    ("tyre_wear_rate_soft",   1.33),
    ("tyre_wear_rate_medium", 1.08),
    ("tyre_wear_rate_hard",   0.89),
]

REGIMES = ["PLAYER", "AI"]
