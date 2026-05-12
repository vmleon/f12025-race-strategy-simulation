from __future__ import annotations

import logging
from collections import Counter

from simulator.car_state import _pace_from_recent_laps
from simulator.engine import MonteCarloEngine
from simulator.models import (
    CarResult,
    PitStrategy,
    RaceSnapshot,
    RankedStrategy,
    StrategyCandidate,
    StrategyEvaluation,
)

logger = logging.getLogger("simulator")

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

        # One-line input summary. Full per-car detail is at DEBUG so it's
        # available when we need it without flooding INFO.
        observed = sum(1 for c in base_snapshot.cars if c.recent_lap_times_ms)
        player_cs = next(
            (c for c in base_snapshot.cars if c.car_index == player_car_index),
            None,
        )
        if player_cs is not None:
            player_pace = _pace_from_recent_laps(
                player_cs.recent_lap_times_ms, base_snapshot.track_id)
            player_pace_src = "observed" if player_cs.recent_lap_times_ms else "default"
            logger.info(
                "strategy.input: lap=%d/%d cars=%d observed_pace=%d candidates=%d "
                "player_car=%d pos=%d pace=%.0fms (%s)",
                base_snapshot.current_lap, base_snapshot.total_laps,
                len(base_snapshot.cars), observed, len(candidates),
                player_cs.car_index, player_cs.position, player_pace, player_pace_src,
            )
        if logger.isEnabledFor(logging.DEBUG):
            for cs in base_snapshot.cars:
                pace = _pace_from_recent_laps(cs.recent_lap_times_ms, base_snapshot.track_id)
                tag = "PLAYER" if not cs.ai_controlled else "AI"
                logger.debug(
                    "strategy.input.car: car=%d pos=%d %s recent_laps_ms=%s "
                    "pace_ms=%.0f compound=%d age=%d",
                    cs.car_index, cs.position, tag,
                    list(cs.recent_lap_times_ms), pace,
                    cs.tyre_compound, cs.tyre_age_laps,
                )

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

            logger.debug(
                "strategy.candidate: %s player_mean_pos=%.2f ci=[%.1f, %.1f] dnf=%.2f iterations=%d",
                candidate.label, player_result.mean_position,
                player_result.ci95_low, player_result.ci95_high,
                player_result.dnf_probability, sim_result.iterations,
            )

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

        # Anomaly: candidates all collapse to roughly the same outcome AND that
        # outcome doesn't match the player's actual current standing. A dominant
        # leader legitimately sees every strategy converge to P1 — only warn
        # when the convergence is also wildly off from the snapshot's truth.
        if len(ranked) >= 2 and player_cs is not None:
            spread = ranked[-1].mean_position - ranked[0].mean_position
            deviation = abs(ranked[0].mean_position - player_cs.position)
            if spread < 0.5 and deviation > 5.0:
                logger.warning(
                    "strategy.degenerate: %d candidates all within %.2f positions "
                    "(mean ~%.2f) but player is currently P%d — engine output is suspicious",
                    len(ranked), spread, ranked[0].mean_position, player_cs.position,
                )

        # Final winner ranking — what the portal will actually show.
        top = ranked[:3]
        summary = " | ".join(
            f"{i+1}) {r.candidate.label} P{r.mean_position:.1f}"
            for i, r in enumerate(top)
        )
        logger.info("strategy.ranked: %s", summary or "(no candidates)")

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
