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
        return 0.0

    def size(self) -> int:
        return len(self._store)

    @staticmethod
    def _key(knob_name: str, regime: str, sector: int) -> str:
        return f"{knob_name}|{regime}|{sector}"

    @staticmethod
    def defaults() -> Coefficients:
        """Creates a Coefficients instance with cold-start defaults for both regimes."""
        c = Coefficients()
        for regime in ("PLAYER", "AI"):
            c.put("tyre_deg_soft", regime, -1, 0.05)
            c.put("tyre_deg_medium", regime, -1, 0.03)
            c.put("tyre_deg_hard", regime, -1, 0.02)
            c.put("fuel_effect", regime, -1, 0.01)
            c.put("front_wing_damage", regime, -1, 0.02)
            c.put("floor_damage", regime, -1, 0.04)
            c.put("engine_damage", regime, -1, 0.01)
            c.put("dirty_air", regime, -1, 0.30)
            c.put("drs_advantage", regime, -1, -0.20)
            c.put("overtake_probability", regime, -1, 0.15)
            c.put("safety_car_rate", regime, -1, 0.01)
        return c
