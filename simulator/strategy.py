from __future__ import annotations

import logging
from collections import Counter

from simulator.car_state import _lap_pace_ms
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

# Finishing-position spread below which two candidates are a statistical tie. Mean
# position over ~1k Monte-Carlo iterations carries roughly this much run-to-run
# noise, so anything closer than this must NOT decide the recommendation — else the
# "best" strategy reshuffles every evaluation and the radio flaps. See A+B fix.
RANK_NOISE_TOL = 0.5


def _rank_key(r: "RankedStrategy"):
    """Deterministic ranking key. Primary: mean finishing position bucketed to the
    noise tolerance, so near-ties group together. Within a tie, prefer the
    conservative plan — fewer stops, then a later first stop, then label — so the
    recommendation is stable run-to-run and never commits to an early/extra stop
    that isn't clearly better."""
    stops = r.candidate.stops
    first_stop_lap = stops[0].on_lap if stops else 0
    return (
        round(r.mean_position / RANK_NOISE_TOL),  # noise bucket — lower is better
        len(stops),                               # fewer stops preferred on a tie
        -first_stop_lap,                          # later first stop preferred on a tie
        r.candidate.label,                        # final deterministic tiebreak
    )


def _insufficient_calibration(player_cs, player_pace_src: str | None) -> bool:
    """True when the ranking can't be trusted because the player's pace isn't
    really calibrated: it fell back to the generic circuit default, OR there is no
    fitted sector-pace baseline for the compound the player is on (so the
    projection rests on a handful of raw recent laps)."""
    if player_pace_src == "circuit_default":
        return True
    if player_cs is not None and not any(b > 0 for b in player_cs.sector_baseline_ms):
        return True
    return False


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
        observed = sum(
            1 for c in base_snapshot.cars if any(c.sector_history_ms))
        baseline_seeded = sum(
            1 for c in base_snapshot.cars if any(b > 0 for b in c.sector_baseline_ms))
        player_cs = next(
            (c for c in base_snapshot.cars if c.car_index == player_car_index),
            None,
        )
        player_pace_src = None
        if player_cs is not None:
            player_pace = _lap_pace_ms(player_cs, base_snapshot.track_id)
            if any(player_cs.sector_history_ms):
                player_pace_src = "observed"
            elif any(b > 0 for b in player_cs.sector_baseline_ms):
                player_pace_src = "baseline"
            else:
                player_pace_src = "circuit_default"
            logger.info(
                "strategy.input: lap=%d/%d cars=%d observed_pace=%d baseline_seeded=%d "
                "candidates=%d player_car=%d pos=%d pace=%.0fms (%s)",
                base_snapshot.current_lap, base_snapshot.total_laps,
                len(base_snapshot.cars), observed, baseline_seeded, len(candidates),
                player_cs.car_index, player_cs.position, player_pace, player_pace_src,
            )
        if logger.isEnabledFor(logging.DEBUG):
            for cs in base_snapshot.cars:
                pace = _lap_pace_ms(cs, base_snapshot.track_id)
                tag = "PLAYER" if not cs.ai_controlled else "AI"
                logger.debug(
                    "strategy.input.car: car=%d pos=%d %s sector_history_ms=%s "
                    "pace_ms=%.0f compound=%d age=%d",
                    cs.car_index, cs.position, tag,
                    cs.sector_history_ms, pace,
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

        # Rank by mean position, but treat sub-noise differences as ties and break
        # them conservatively (see _rank_key). A plain mean-position sort let
        # Monte-Carlo float noise reorder near-identical candidates every run, which
        # is what made the spoken plan flap between box laps lap-to-lap.
        results.sort(key=_rank_key)
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

        # Insufficient calibration: the player's pace isn't really calibrated (fell
        # back to the circuit default, or there's no fitted baseline for the compound
        # it's on), so the ranked numbers are fake-precise. The panel surfaces this
        # instead of the strategies, and it lets the radio layer hold rather than
        # churn on noise.
        insufficient_calibration = _insufficient_calibration(player_cs, player_pace_src)

        # Top two within noise → the recommendation is a coin-flip; the conservative
        # tie-break above keeps it stable, but log it for observability.
        if len(ranked) >= 2 and (ranked[1].mean_position - ranked[0].mean_position) < RANK_NOISE_TOL:
            logger.info(
                "strategy.low_confidence: top-2 within %.2f positions (%.2f vs %.2f) — "
                "recommendation is a statistical tie, held to the conservative option",
                RANK_NOISE_TOL, ranked[0].mean_position, ranked[1].mean_position,
            )

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
        if insufficient_calibration:
            logger.info(
                "strategy.insufficient_calibration: player car=%d pace is circuit "
                "default — surfacing insufficient-calibration to the panel",
                player_car_index,
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
            insufficient_calibration=insufficient_calibration,
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
