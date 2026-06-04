from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_serializer


# ── Input: Race Snapshot ─────────────────────────────────────────────────────


class PitStop(BaseModel):
    on_lap: int = Field(alias="onLap")
    new_compound: int = Field(alias="newCompound")
    repair_front_wing: bool = Field(default=False, alias="repairFrontWing")

    model_config = {"populate_by_name": True}


class PitStrategy(BaseModel):
    target_car_index: int = Field(alias="targetCarIndex")
    stops: list[PitStop]

    model_config = {"populate_by_name": True}


class Penalty(BaseModel):
    """A pending penalty for a car.

    penalty_type: "time" | "drive_through" | "stop_go"
    seconds: penalty seconds (for time penalty: seconds added to result;
             for stop_go: stationary stop duration, typically 5 or 10)
    laps_to_serve: laps remaining to serve before disqualification
                   (only relevant for drive_through / stop_go)
    """
    penalty_type: Literal["time", "drive_through", "stop_go"] = Field(alias="penaltyType")
    seconds: float = 0.0
    laps_to_serve: int = Field(default=3, alias="lapsToServe")

    model_config = {"populate_by_name": True}


class TyreSetInfo(BaseModel):
    visual_tyre_compound: int = Field(alias="visualTyreCompound")
    available: bool
    wear: int
    life_span: int = Field(alias="lifeSpan")
    usable_life: int = Field(alias="usableLife")
    lap_delta_time: int = Field(alias="lapDeltaTime")
    fitted: bool

    model_config = {"populate_by_name": True}


class CarSnapshot(BaseModel):
    car_index: int = Field(alias="carIndex")
    driver_name: str = Field(alias="driverName")
    ai_controlled: bool = Field(alias="aiControlled")
    position: int
    tyre_compound: int = Field(alias="tyreCompound")
    tyre_age_laps: int = Field(alias="tyreAgeLaps")
    fuel_kg: float = Field(alias="fuelKg")
    fuel_burn_per_sector_kg: float = Field(alias="fuelBurnPerSectorKg")
    front_wing_damage: int = Field(alias="frontWingDamage")
    floor_damage: int = Field(alias="floorDamage")
    engine_damage: int = Field(alias="engineDamage")
    num_pit_stops: int = Field(alias="numPitStops")
    total_time_ms: float = Field(alias="totalTimeMs")
    penalties: list[Penalty] = Field(default_factory=list)
    tyre_sets: list[TyreSetInfo] = Field(default_factory=list, alias="tyreSets")
    # Recent valid sector times per sector (index 0/1/2), newest first, from the
    # backend's SectorHistoryLookup. Inner lists empty until laps are observed.
    sector_history_ms: list[list[int]] = Field(
        default_factory=lambda: [[], [], []], alias="sectorHistoryMs")
    # Calibrated mean sector baseline (ms) per sector (index 0/1/2). 0 where none.
    sector_baseline_ms: list[int] = Field(
        default_factory=lambda: [0, 0, 0], alias="sectorBaselineMs")
    # Calibrated perfect (min clean) sector time (ms) per sector (index 0/1/2).
    # Used as the per-sector floor. 0 where none.
    perfect_sector_ms: list[int] = Field(
        default_factory=lambda: [0, 0, 0], alias="perfectSectorMs")

    model_config = {"populate_by_name": True}


class RaceSnapshot(BaseModel):
    track_id: int = Field(alias="trackId")
    total_laps: int = Field(alias="totalLaps")
    current_lap: int = Field(alias="currentLap")
    current_sector: int = Field(alias="currentSector")
    weather: int
    track_temp: int = Field(alias="trackTemp")
    air_temp: int = Field(alias="airTemp")
    safety_car: bool = Field(alias="safetyCar")
    cars: list[CarSnapshot]
    pit_strategy: PitStrategy | None = Field(default=None, alias="pitStrategy")

    model_config = {"populate_by_name": True}


# ── Output: Simulation Result ────────────────────────────────────────────────


class CarResult(BaseModel):
    car_index: int = Field(alias="carIndex")
    driver_name: str = Field(alias="driverName")
    mean_position: float = Field(alias="meanPosition")
    position_std_dev: float = Field(alias="positionStdDev")
    ci95_low: float = Field(alias="ci95Low")
    ci95_high: float = Field(alias="ci95High")
    dnf_probability: float = Field(alias="dnfProbability")
    top3_probability: float = Field(alias="top3Probability")
    points_finish_probability: float = Field(alias="pointsFinishProbability")
    position_distribution: dict[int, float] = Field(alias="positionDistribution")

    @field_serializer("position_distribution")
    @classmethod
    def _serialize_position_distribution(cls, v: dict[int, float]) -> dict[str, float]:
        return {str(k): v2 for k, v2 in v.items()}

    model_config = {"populate_by_name": True}


class SimulationResult(BaseModel):
    iterations: int
    converged: bool
    wall_clock_ms: int = Field(alias="wallClockMs")
    cars: list[CarResult]

    model_config = {"populate_by_name": True}


# ── Output: Strategy Evaluation ──────────────────────────────────────────────


class StrategyCandidate(BaseModel):
    label: str
    stops: list[PitStop]


class RankedStrategy(BaseModel):
    rank: int
    candidate: StrategyCandidate
    mean_position: float = Field(alias="meanPosition")
    position_std_dev: float = Field(alias="positionStdDev")
    ci95_low: float = Field(alias="ci95Low")
    ci95_high: float = Field(alias="ci95High")
    dnf_probability: float = Field(alias="dnfProbability")
    top3_probability: float = Field(alias="top3Probability")
    points_finish_probability: float = Field(alias="pointsFinishProbability")
    expected_points: float = Field(alias="expectedPoints")

    model_config = {"populate_by_name": True}


class StrategyEvaluation(BaseModel):
    player_car_index: int = Field(alias="playerCarIndex")
    strategies: list[RankedStrategy]

    model_config = {"populate_by_name": True}


# ── Strategy Evaluation Request ──────────────────────────────────────────────


class StrategyEvaluationRequest(BaseModel):
    snapshot: RaceSnapshot
    player_car_index: int = Field(alias="playerCarIndex")
    candidates: list[StrategyCandidate]

    model_config = {"populate_by_name": True}


class AutoStrategyRequest(BaseModel):
    snapshot: RaceSnapshot
    player_car_index: int = Field(alias="playerCarIndex")

    model_config = {"populate_by_name": True}
