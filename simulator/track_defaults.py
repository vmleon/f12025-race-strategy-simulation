"""Per-circuit pace defaults derived from real F1 fastest-lap times.

These act as the final fallback in the simulator's pace estimation chain
when no observed laps (and, in a future patch, no calibrated baselines) are
available. Values are real F1 race fastest laps inflated by ~2 % so the
default is slightly slower than aspirational. Refresh when F1 records shift
meaningfully or the game's underlying car model changes regulation year.

Source: F1 official race fastest-lap records as of the 2024 season. Tracks
F1 doesn't currently visit (Sochi, Hockenheim, Paul Ricard) retain their
most recent historic record. Short layouts and never-raced circuits
(Silverstone Short, Hanoi, etc.) are best-effort estimates from the closest
comparable layout.

Keys align with the backend's `GameMappings.TRACKS` map
(`backend/src/main/java/.../GameMappings.java`).
"""

# Fallback when track_id is not in the table — preserves legacy 90 s behaviour
# instead of crashing on unknown tracks.
DEFAULT_LAP_MS = 90_000.0

# track_id → expected lap time in milliseconds (~real F1 FL × 1.02).
_CIRCUIT_DEFAULTS: dict[int, int] = {
    0:  81_400,   # Melbourne
    1:  94_600,   # Paul Ricard
    2:  94_000,   # Shanghai
    3:  93_200,   # Bahrain
    4:  75_500,   # Catalunya
    5:  74_400,   # Monaco
    6:  74_600,   # Montreal
    7:  88_800,   # Silverstone
    8:  73_200,   # Hockenheim
    9:  78_100,   # Hungaroring
    10: 105_800,  # Spa
    11: 82_600,   # Monza
    12: 96_400,   # Singapore
    13: 92_700,   # Suzuka
    14: 87_300,   # Abu Dhabi
    15: 95_900,   # Austin
    16: 71_900,   # Interlagos
    17: 66_900,   # Red Bull Ring
    18: 96_900,   # Sochi (no longer on F1 calendar — last F1 lap retained)
    19: 79_400,   # Mexico City
    20: 105_100,  # Baku
    21: 56_100,   # Sakhir Short (Bahrain Outer, 2020 F1)
    22: 66_000,   # Silverstone Short (estimate — never F1-raced)
    23: 66_000,   # Austin Short (estimate — never F1-raced)
    24: 64_000,   # Suzuka Short (estimate — never F1-raced)
    25: 100_000,  # Hanoi (never F1-raced — planned-layout estimate)
    26: 72_500,   # Zandvoort
    27: 77_000,   # Imola
    28: 80_300,   # Portimao (last F1 visit 2021)
    29: 92_500,   # Jeddah
    30: 91_500,   # Miami
    31: 96_800,   # Las Vegas
    32: 85_700,   # Losail
    33: 85_700,   # Lusail (alternative naming for Losail in some game builds)
}


def circuit_default_ms(track_id: int) -> float:
    """Expected lap time in ms for the given track_id.

    Falls back to {@link DEFAULT_LAP_MS} when the track is unknown so the
    simulator never crashes on an unfamiliar circuit — it just loses the
    precision benefit of the per-circuit default.
    """
    return float(_CIRCUIT_DEFAULTS.get(track_id, DEFAULT_LAP_MS))
