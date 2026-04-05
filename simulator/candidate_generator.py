from __future__ import annotations

import logging

from simulator.models import (
    CarSnapshot,
    PitStop,
    RaceSnapshot,
    StrategyCandidate,
    TyreSetInfo,
)

logger = logging.getLogger("simulator")

# Visual compound codes
SOFT = 16
MEDIUM = 17
HARD = 18

COMPOUND_NAMES = {SOFT: "S", MEDIUM: "M", HARD: "H", 7: "I", 8: "W"}

# Pruning thresholds
MAX_CANDIDATES = 50
MIN_LAPS_FOR_TWO_STOP = 15
MIN_STINT_LAPS = 5
LAP_DELTA_THRESHOLD_MS = 5000  # Exclude compounds slower by >5s per lap


def generate_candidates(
    snapshot: RaceSnapshot, player_car_index: int
) -> list[StrategyCandidate]:
    """Generate plausible pit strategy candidates based on race state and tyre availability."""
    player = _find_player(snapshot.cars, player_car_index)
    if player is None:
        logger.info("candidate_generator: player car %d not found in snapshot", player_car_index)
        return []

    remaining_laps = snapshot.total_laps - snapshot.current_lap
    if remaining_laps <= 1:
        logger.info(
            "candidate_generator: remaining_laps=%d (current=%d, total=%d) too few, returning empty",
            remaining_laps, snapshot.current_lap, snapshot.total_laps,
        )
        return []

    current_compound = player.tyre_compound
    has_used_multiple_compounds = player.num_pit_stops > 0

    # Determine the fitted set's usable life for 0-stop feasibility
    fitted_sets = [ts for ts in player.tyre_sets if ts.fitted]
    fitted_usable_life = fitted_sets[0].usable_life if fitted_sets else 0

    logger.info(
        "candidate_generator: player=%d remaining_laps=%d current_compound=%d pit_stops=%d "
        "tyre_sets=%d fitted_usable_life=%d",
        player_car_index, remaining_laps, current_compound, player.num_pit_stops,
        len(player.tyre_sets), fitted_usable_life,
    )

    candidates: list[StrategyCandidate] = []

    # 0-stop: only if two-compound rule is already met and tyres can last
    if has_used_multiple_compounds and remaining_laps <= fitted_usable_life:
        candidates.append(StrategyCandidate(label="No stop", stops=[]))

    available_compounds = _get_available_compounds(player.tyre_sets)
    logger.info(
        "candidate_generator: available_compounds=%s (from %d tyre_sets)",
        available_compounds, len(player.tyre_sets),
    )
    if not available_compounds:
        if player.tyre_sets:
            # Dump per-set details so we can see why all were filtered out
            for i, ts in enumerate(player.tyre_sets):
                logger.info(
                    "  tyre_set[%d] visual=%d available=%s fitted=%s lap_delta=%d usable_life=%d",
                    i, ts.visual_tyre_compound, ts.available, ts.fitted,
                    ts.lap_delta_time, ts.usable_life,
                )
        logger.info("candidate_generator: returning %d candidates (no available compounds)", len(candidates))
        return candidates

    # 1-stop strategies
    pit_lap_step = _compute_lap_step(remaining_laps)
    for compound, name in available_compounds.items():
        if compound == current_compound and not has_used_multiple_compounds:
            # Would violate two-compound rule unless we've already used another
            continue
        for pit_lap in range(
            snapshot.current_lap + MIN_STINT_LAPS,
            snapshot.total_laps - 1,
            pit_lap_step,
        ):
            label = f"1-stop L{pit_lap} {COMPOUND_NAMES.get(current_compound, '?')}->{name}"
            candidates.append(
                StrategyCandidate(
                    label=label,
                    stops=[PitStop(on_lap=pit_lap, new_compound=compound)],
                )
            )

    # 2-stop strategies: only if enough laps remain
    if remaining_laps >= MIN_LAPS_FOR_TWO_STOP:
        two_stop_step = max(pit_lap_step, 5)
        compound_list = list(available_compounds.items())
        for c1_compound, c1_name in compound_list:
            for c2_compound, c2_name in compound_list:
                # Ensure two-compound rule is met across all stints
                compounds_used = {current_compound, c1_compound, c2_compound}
                if not has_used_multiple_compounds and len(compounds_used) < 2:
                    continue
                for lap1 in range(
                    snapshot.current_lap + MIN_STINT_LAPS,
                    snapshot.total_laps - MIN_STINT_LAPS - 1,
                    two_stop_step,
                ):
                    for lap2 in range(
                        lap1 + MIN_STINT_LAPS,
                        snapshot.total_laps - 1,
                        two_stop_step,
                    ):
                        label = (
                            f"2-stop L{lap1}/{lap2} "
                            f"{COMPOUND_NAMES.get(current_compound, '?')}->"
                            f"{c1_name}->{c2_name}"
                        )
                        candidates.append(
                            StrategyCandidate(
                                label=label,
                                stops=[
                                    PitStop(on_lap=lap1, new_compound=c1_compound),
                                    PitStop(on_lap=lap2, new_compound=c2_compound),
                                ],
                            )
                        )

    pre_truncate = len(candidates)

    # Truncate to max candidates, preferring 1-stop over 2-stop
    if len(candidates) > MAX_CANDIDATES:
        zero_one = [c for c in candidates if len(c.stops) <= 1]
        two = [c for c in candidates if len(c.stops) == 2]
        remaining_slots = MAX_CANDIDATES - len(zero_one)
        candidates = zero_one + two[:remaining_slots]

    final = candidates[:MAX_CANDIDATES]
    logger.info(
        "candidate_generator: generated %d candidates (pre-truncate=%d, after cap=%d)",
        len(final), pre_truncate, len(final),
    )
    return final


def _find_player(cars: list[CarSnapshot], index: int) -> CarSnapshot | None:
    for car in cars:
        if car.car_index == index:
            return car
    return None


def _get_available_compounds(tyre_sets: list[TyreSetInfo]) -> dict[int, str]:
    """Returns dict of compound code -> display name for available, non-fitted compounds
    that pass the lap delta threshold."""
    compounds: dict[int, str] = {}
    for ts in tyre_sets:
        if not ts.available or ts.fitted:
            continue
        if abs(ts.lap_delta_time) > LAP_DELTA_THRESHOLD_MS:
            continue
        if ts.visual_tyre_compound not in compounds:
            compounds[ts.visual_tyre_compound] = COMPOUND_NAMES.get(
                ts.visual_tyre_compound, f"C{ts.visual_tyre_compound}"
            )
    return compounds


def _compute_lap_step(remaining_laps: int) -> int:
    """Compute lap increment for pit windows to keep candidate count manageable."""
    if remaining_laps <= 15:
        return 2
    if remaining_laps <= 30:
        return 3
    return 5
