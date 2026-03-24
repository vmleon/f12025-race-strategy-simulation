from __future__ import annotations

from simulator.models import CarSnapshot


class CarState:
    """Mutable state of a single car during a Monte Carlo iteration."""

    __slots__ = (
        "car_index", "driver_name", "ai_controlled",
        "position", "tyre_compound", "tyre_age_laps",
        "fuel_kg", "fuel_burn_per_sector_kg",
        "front_wing_damage", "floor_damage", "engine_damage",
        "num_pit_stops", "retired", "total_time_ms", "current_lap",
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

    @property
    def regime(self) -> str:
        return "AI" if self.ai_controlled else "PLAYER"

    @staticmethod
    def from_snapshot(cs: CarSnapshot, current_lap: int) -> CarState:
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
        )
