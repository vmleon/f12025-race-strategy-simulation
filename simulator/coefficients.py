from __future__ import annotations


class Coefficients:
    """In-memory store of calibration coefficients for a specific track.

    Coefficients are keyed by (knob_name, regime, sector_number).
    Sector-wide coefficients use sector_number = -1.
    """

    def __init__(self) -> None:
        self._store: dict[str, float] = {}

    def put(self, knob_name: str, regime: str, sector: int, value: float) -> None:
        self._store[self._key(knob_name, regime, sector)] = value

    def get(self, knob_name: str, regime: str, sector: int = -1) -> float:
        v = self._store.get(self._key(knob_name, regime, sector))
        if v is not None:
            return v
        if sector != -1:
            v = self._store.get(self._key(knob_name, regime, -1))
            if v is not None:
                return v
        # Fall back from PLAYER to AI when player data is missing
        if regime == "PLAYER":
            return self.get(knob_name, "AI", sector)
        return 0.0

    def size(self) -> int:
        return len(self._store)

    @staticmethod
    def _key(knob_name: str, regime: str, sector: int) -> str:
        return f"{knob_name}|{regime}|{sector}"

    @staticmethod
    def defaults() -> Coefficients:
        """Cold-start defaults — only the three knob families currently calibrated:
        tyre_deg_*, fuel_effect, pit_stop_time_loss. Damage / dirty-air / DRS /
        overtake / safety-car effects were dropped from the engine in the
        Option-C simplification; their defaults are no longer needed.
        """
        c = Coefficients()
        for regime in ("PLAYER", "AI"):
            c.put("tyre_deg_soft", regime, -1, 0.05)
            c.put("tyre_deg_medium", regime, -1, 0.03)
            c.put("tyre_deg_hard", regime, -1, 0.02)
            # Wet-weather compounds — placeholder until we have wet-session data.
            c.put("tyre_deg_intermediate", regime, -1, 0.04)
            c.put("tyre_deg_wet", regime, -1, 0.04)
            c.put("fuel_effect", regime, -1, 0.01)
            c.put("pit_stop_time_loss", regime, -1, 22_000.0)
            # Wear-rate %/lap (most-worn wheel). Defaults chosen so laps-to-cliff at
            # the 40% threshold ≈ the old hardcoded lifespans (S30/M37/H45).
            c.put("tyre_wear_rate_soft", regime, -1, 1.33)
            c.put("tyre_wear_rate_medium", regime, -1, 1.08)
            c.put("tyre_wear_rate_hard", regime, -1, 0.89)
        return c
