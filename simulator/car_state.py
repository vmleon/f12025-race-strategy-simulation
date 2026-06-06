from __future__ import annotations

from simulator.models import CarSnapshot, Penalty
from simulator.track_defaults import circuit_default_ms

SECTORS = 3


class CarState:
    """Mutable state of a single car during a Monte Carlo iteration."""

    __slots__ = (
        "car_index", "driver_name", "ai_controlled",
        "position", "tyre_compound", "tyre_age_laps",
        "fuel_kg", "fuel_burn_per_sector_kg",
        "front_wing_damage", "floor_damage", "engine_damage",
        "num_pit_stops", "retired", "total_time_ms", "current_lap",
        "pending_penalties", "penalty_time_ms",
        "sector_pace_ms", "perfect_sector_ms",
        "age_ref", "fuel_ref",
    )

    def __init__(
        self,
        car_index: int,
        driver_name: str,
        ai_controlled: bool,
        position: int,
        tyre_compound: int,
        tyre_age_laps: int,
        fuel_kg: float,
        fuel_burn_per_sector_kg: float,
        front_wing_damage: int,
        floor_damage: int,
        engine_damage: int,
        num_pit_stops: int,
        total_time_ms: float,
        current_lap: int,
        sector_pace_ms: list[float],
        perfect_sector_ms: list[float],
        pending_penalties: list[Penalty] | None = None,
        penalty_time_ms: float = 0.0,
        age_ref: float = 0.0,
        fuel_ref: float = 0.0,
    ) -> None:
        self.car_index = car_index
        self.driver_name = driver_name
        self.ai_controlled = ai_controlled
        self.position = position
        self.tyre_compound = tyre_compound
        self.tyre_age_laps = tyre_age_laps
        self.fuel_kg = fuel_kg
        self.fuel_burn_per_sector_kg = fuel_burn_per_sector_kg
        self.front_wing_damage = front_wing_damage
        self.floor_damage = floor_damage
        self.engine_damage = engine_damage
        self.num_pit_stops = num_pit_stops
        self.retired = False
        self.total_time_ms = total_time_ms
        self.current_lap = current_lap
        self.sector_pace_ms = sector_pace_ms
        self.perfect_sector_ms = perfect_sector_ms
        self.pending_penalties = list(pending_penalties) if pending_penalties else []
        self.penalty_time_ms = penalty_time_ms
        # Reference conditions the base pace was observed/calibrated at. tyre_deg and
        # fuel_effect are applied as deltas from these so they aren't double-counted
        # (base_sector_pace already embeds the age/fuel at the reference). age_ref
        # resets to 0 on a pit stop (fresh stint); fuel_ref is fixed (no refuelling).
        self.age_ref = age_ref
        self.fuel_ref = fuel_ref

    @property
    def regime(self) -> str:
        return "AI" if self.ai_controlled else "PLAYER"

    @staticmethod
    def from_snapshot(cs: CarSnapshot, current_lap: int, track_id: int) -> CarState:
        penalties = list(cs.penalties) if cs.penalties else []
        penalty_time_ms = 0.0
        for p in penalties:
            if p.penalty_type == "time":
                penalty_time_ms += p.seconds * 1000.0
        active_penalties = [p for p in penalties if p.penalty_type != "time"]
        sector_pace = _sector_pace_ms(cs.sector_history_ms, cs.sector_baseline_ms, track_id)
        return CarState(
            car_index=cs.car_index,
            driver_name=cs.driver_name,
            ai_controlled=cs.ai_controlled,
            position=cs.position,
            tyre_compound=cs.tyre_compound,
            tyre_age_laps=cs.tyre_age_laps,
            fuel_kg=cs.fuel_kg,
            fuel_burn_per_sector_kg=cs.fuel_burn_per_sector_kg,
            front_wing_damage=cs.front_wing_damage,
            floor_damage=cs.floor_damage,
            engine_damage=cs.engine_damage,
            num_pit_stops=cs.num_pit_stops,
            total_time_ms=cs.total_time_ms,
            current_lap=current_lap,
            sector_pace_ms=sector_pace,
            perfect_sector_ms=_perfect_sector_floor_ms(cs.perfect_sector_ms, sector_pace),
            pending_penalties=active_penalties,
            penalty_time_ms=penalty_time_ms,
            age_ref=float(cs.tyre_age_laps),
            fuel_ref=cs.fuel_kg,
        )


def _sector_pace_ms(
    sector_history_ms: list[list[int]],
    sector_baseline_ms: list[int],
    track_id: int,
) -> list[float]:
    """Per-sector base pace: median of recent observed sector times, else the
    calibrated sector baseline, else the per-circuit default split evenly across
    the three sectors. A bad single sector only affects that sector's pace.
    """
    default_sector = circuit_default_ms(track_id) / SECTORS
    out: list[float] = []
    for s in range(SECTORS):
        observed = [t for t in sector_history_ms[s] if t > 0] if s < len(sector_history_ms) else []
        if observed:
            observed.sort()
            out.append(float(observed[len(observed) // 2]))
        elif s < len(sector_baseline_ms) and sector_baseline_ms[s] > 0:
            out.append(float(sector_baseline_ms[s]))
        else:
            out.append(default_sector)
    return out


def _perfect_sector_floor_ms(
    perfect_sector_ms: list[int],
    sector_pace_ms: list[float],
) -> list[float]:
    """Per-sector floor: the calibrated perfect (min clean) sector when present,
    else 90% of the sector's base pace (the legacy clamp)."""
    out: list[float] = []
    for s in range(SECTORS):
        p = perfect_sector_ms[s] if s < len(perfect_sector_ms) else 0
        out.append(float(p) if p > 0 else sector_pace_ms[s] * 0.9)
    return out


def _lap_pace_ms(cs: CarSnapshot, track_id: int) -> float:
    """Diagnostics helper: a lap-level pace estimate = Σ of the per-sector base
    pace. Used by strategy logging, not by the engine."""
    return sum(_sector_pace_ms(cs.sector_history_ms, cs.sector_baseline_ms, track_id))
