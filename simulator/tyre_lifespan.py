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


def compound_lifespan(compound: int) -> int:
    """Return the expected lifespan in laps for the given visual compound code."""
    return COMPOUND_LIFESPAN_LAPS.get(compound, DEFAULT_COMPOUND_LIFESPAN_LAPS)
