"""Game-imposed tyre lifespans (source: design/11-GAME-ASSUMPTIONS.md).

Used by the engine for AI pit decisions and by the candidate generator
to prune infeasible strategies.
"""

# Visual compound code -> expected lifespan in laps before the performance cliff.
COMPOUND_LIFESPAN_LAPS = {
    16: 30,  # Soft
    17: 37,  # Medium
    18: 45,  # Hard (unverified - extrapolated from Soft+Medium)
    7: 30,   # Intermediate (unverified placeholder)
    8: 30,   # Wet (unverified placeholder)
}

DEFAULT_COMPOUND_LIFESPAN_LAPS = 37

# Wear-% at which the tyre falls off the cliff (measured on the most-worn wheel).
# Hardcoded per-compound and tunable here — NOT calibrated (the cliff location can't
# be learned from short FP runs; only the wear-rate is). Combined with the calibrated
# wear-rate it yields a per-circuit stint cap: laps-to-cliff = cliffPct / wear-rate.
CLIFF_WEAR_PCT = {
    16: 40.0,  # Soft
    17: 40.0,  # Medium
    18: 40.0,  # Hard
    7: 60.0,   # Intermediate (placeholder)
    8: 60.0,   # Wet (placeholder)
}
DEFAULT_CLIFF_WEAR_PCT = 40.0

# Visual compound code -> calibrated wear-rate knob name (%/lap, sector-wide).
WEAR_RATE_KNOBS = {
    16: "tyre_wear_rate_soft",
    17: "tyre_wear_rate_medium",
    18: "tyre_wear_rate_hard",
}


def compound_lifespan(compound: int) -> int:
    """Return the expected lifespan in laps for the given visual compound code."""
    return COMPOUND_LIFESPAN_LAPS.get(compound, DEFAULT_COMPOUND_LIFESPAN_LAPS)


def laps_to_cliff(compound: int, wear_rate_pct_per_lap: float) -> int:
    """Laps until the most-worn wheel reaches the compound's cliff threshold, from a
    fresh tyre. Falls back to the hardcoded lifespan when the wear-rate is uncalibrated
    (<= 0)."""
    if wear_rate_pct_per_lap and wear_rate_pct_per_lap > 0:
        cliff = CLIFF_WEAR_PCT.get(compound, DEFAULT_CLIFF_WEAR_PCT)
        return max(1, round(cliff / wear_rate_pct_per_lap))
    return compound_lifespan(compound)


def stint_cap_laps(compound: int, coefficients, regime: str) -> int:
    """Data-driven max stint length for a compound: cliffPct / calibrated wear-rate,
    or the hardcoded lifespan when wear-rate is uncalibrated or coefficients are absent.
    `coefficients` is a simulator.coefficients.Coefficients (or None)."""
    wear_rate = 0.0
    knob = WEAR_RATE_KNOBS.get(compound)
    if coefficients is not None and knob is not None:
        wear_rate = coefficients.get(knob, regime)
    return laps_to_cliff(compound, wear_rate)
