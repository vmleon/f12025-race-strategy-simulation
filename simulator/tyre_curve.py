from __future__ import annotations


class TyreCurves:
    """Per-(compound, regime, sector) tyre-age pace offsets (ms vs baseline).

    Keyed bins are age->offset. offset() clamps ages at/below the first bin to the
    first value (out-lap), linearly interpolates interior gaps, and returns None
    above the last bin so the engine can extrapolate with the deg knob. PLAYER
    falls back to AI."""

    def __init__(self) -> None:
        self._store: dict[tuple, dict[int, float]] = {}

    def put(self, compound: int, regime: str, sector: int, age: int, offset_ms: float) -> None:
        self._store.setdefault((compound, regime, sector), {})[age] = offset_ms

    def _bins(self, compound: int, regime: str, sector: int) -> dict[int, float] | None:
        bins = self._store.get((compound, regime, sector))
        if bins is None and regime == "PLAYER":
            bins = self._store.get((compound, "AI", sector))
        return bins

    def max_age(self, compound: int, regime: str, sector: int) -> int | None:
        bins = self._bins(compound, regime, sector)
        return max(bins) if bins else None

    def offset(self, compound: int, regime: str, sector: int, age: float) -> float | None:
        bins = self._bins(compound, regime, sector)
        if not bins:
            return None
        lo, hi = min(bins), max(bins)
        if age <= lo:
            return bins[lo]
        if age > hi:
            return None
        if age in bins:
            return bins[age]
        below = max(k for k in bins if k < age)
        above = min(k for k in bins if k > age)
        t = (age - below) / (above - below)
        return bins[below] + t * (bins[above] - bins[below])
