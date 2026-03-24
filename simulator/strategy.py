from __future__ import annotations

from collections import Counter

from simulator.engine import MonteCarloEngine
from simulator.models import (
    CarResult,
    PitStrategy,
    RaceSnapshot,
    RankedStrategy,
    StrategyCandidate,
    StrategyEvaluation,
)

# F1 points system (positions 1-10)
POINTS = [25.0, 18.0, 15.0, 12.0, 10.0, 8.0, 6.0, 4.0, 2.0, 1.0]

COMPOUND_NAMES = {16: "Soft", 17: "Medium", 18: "Hard"}


def expected_points(car_result: CarResult) -> float:
    pts = 0.0
    for pos, prob in car_result.position_distribution.items():
        if 1 <= pos <= len(POINTS):
            pts += POINTS[pos - 1] * prob
    return pts


class StrategyEvaluator:
    """Evaluates multiple candidate pit strategies by running a Monte Carlo
    simulation for each one and ranking by expected finishing position."""

    def __init__(self, engine: MonteCarloEngine) -> None:
        self.engine = engine

    def evaluate(
        self,
        base_snapshot: RaceSnapshot,
        player_car_index: int,
        candidates: list[StrategyCandidate],
    ) -> StrategyEvaluation:
        results: list[RankedStrategy] = []

        for candidate in candidates:
            pit_strategy = PitStrategy(
                target_car_index=player_car_index,
                stops=candidate.stops,
            )
            snapshot = RaceSnapshot(
                track_id=base_snapshot.track_id,
                total_laps=base_snapshot.total_laps,
                current_lap=base_snapshot.current_lap,
                current_sector=base_snapshot.current_sector,
                weather=base_snapshot.weather,
                track_temp=base_snapshot.track_temp,
                air_temp=base_snapshot.air_temp,
                safety_car=base_snapshot.safety_car,
                cars=base_snapshot.cars,
                pit_strategy=pit_strategy,
            )

            sim_result = self.engine.simulate(snapshot)
            player_result = self._find_player_result(sim_result.cars, player_car_index)

            results.append(
                RankedStrategy(
                    rank=0,
                    candidate=candidate,
                    mean_position=player_result.mean_position,
                    position_std_dev=player_result.position_std_dev,
                    ci95_low=player_result.ci95_low,
                    ci95_high=player_result.ci95_high,
                    dnf_probability=player_result.dnf_probability,
                    top3_probability=player_result.top3_probability,
                    points_finish_probability=player_result.points_finish_probability,
                    expected_points=expected_points(player_result),
                )
            )

        # Rank by mean position ascending (lower = better)
        results.sort(key=lambda r: r.mean_position)
        ranked = [
            RankedStrategy(
                rank=i + 1,
                candidate=r.candidate,
                mean_position=r.mean_position,
                position_std_dev=r.position_std_dev,
                ci95_low=r.ci95_low,
                ci95_high=r.ci95_high,
                dnf_probability=r.dnf_probability,
                top3_probability=r.top3_probability,
                points_finish_probability=r.points_finish_probability,
                expected_points=r.expected_points,
            )
            for i, r in enumerate(results)
        ]

        return StrategyEvaluation(
            player_car_index=player_car_index,
            strategies=ranked,
        )

    @staticmethod
    def _find_player_result(
        cars: list[CarResult], player_car_index: int
    ) -> CarResult:
        for car in cars:
            if car.car_index == player_car_index:
                return car
        raise ValueError(
            f"Player car {player_car_index} not found in simulation results"
        )


class TyreSet:
    """Represents an available tyre set for feasibility checking."""

    __slots__ = ("set_index", "compound", "wear", "usable_life")

    def __init__(
        self, set_index: int, compound: int, wear: float, usable_life: int
    ) -> None:
        self.set_index = set_index
        self.compound = compound
        self.wear = wear
        self.usable_life = usable_life


def check_feasibility(
    candidate: StrategyCandidate, available_sets: list[TyreSet]
) -> str | None:
    """Returns None if feasible, or a rejection reason string if not."""
    compound_counts: Counter[int] = Counter(s.compound for s in available_sets)
    needed: Counter[int] = Counter(stop.new_compound for stop in candidate.stops)

    for compound, needed_count in needed.items():
        available = compound_counts.get(compound, 0)
        if available < needed_count:
            name = COMPOUND_NAMES.get(compound, f"Compound-{compound}")
            return (
                f"Need {needed_count} {name} set(s) but only {available} available"
            )
    return None
