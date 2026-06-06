from __future__ import annotations

import logging

from simulator.models import (
    CarSnapshot,
    PitStop,
    RaceSnapshot,
    StrategyCandidate,
    TyreSetInfo,
)
from simulator.tyre_lifespan import stint_cap_laps

logger = logging.getLogger("simulator")

# Visual compound codes
SOFT = 16
MEDIUM = 17
HARD = 18
INTERMEDIATE = 7
WET = 8

COMPOUND_NAMES = {SOFT: "S", MEDIUM: "M", HARD: "H", INTERMEDIATE: "I", WET: "W"}
DRY_COMPOUNDS = {SOFT, MEDIUM, HARD}
WET_COMPOUNDS = {INTERMEDIATE, WET}

# F1 game weather codes: 0=clear, 1=light cloud, 2=overcast, 3=light rain, 4=heavy rain, 5=storm
WET_WEATHER_THRESHOLD = 3

# Pruning thresholds
MAX_CANDIDATES = 50
MIN_LAPS_FOR_TWO_STOP = 15
MIN_STINT_LAPS = 3              # Minimum first/middle stint length
MIN_FINAL_STINT_LAPS = 3        # Min racing laps after the last pit
LAP_DELTA_THRESHOLD_MS = 5000   # Exclude compounds slower by >5s per lap
REPAIR_DAMAGE_THRESHOLD = 20    # Front-wing damage % at which repair stops are proposed


def generate_candidates(
    snapshot: RaceSnapshot, player_car_index: int, coefficients=None
) -> list[StrategyCandidate]:
    """Generate plausible pit strategy candidates based on race state and tyre availability.

    `coefficients` (simulator.coefficients.Coefficients or None) drives the data-driven
    stint cap used for lifespan pruning; without it the hardcoded lifespan is used."""
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
    is_wet = snapshot.weather >= WET_WEATHER_THRESHOLD

    # Determine the fitted set's usable life for 0-stop feasibility
    fitted_sets = [ts for ts in player.tyre_sets if ts.fitted]
    fitted_usable_life = fitted_sets[0].usable_life if fitted_sets else 0

    logger.info(
        "candidate_generator: player=%d remaining_laps=%d current_compound=%d pit_stops=%d "
        "tyre_sets=%d fitted_usable_life=%d weather=%d",
        player_car_index, remaining_laps, current_compound, player.num_pit_stops,
        len(player.tyre_sets), fitted_usable_life, snapshot.weather,
    )

    candidates: list[StrategyCandidate] = []

    # 0-stop: feasible if the fitted tyre can last the remaining distance AND any
    # of: two-compound rule already met, wet conditions waive it, or there's no
    # pit window left (sprint / very short race where no 1-stop slot fits between
    # MIN_STINT_LAPS and MIN_FINAL_STINT_LAPS).
    no_pit_window = (
        snapshot.current_lap + MIN_STINT_LAPS
        >= snapshot.total_laps - MIN_FINAL_STINT_LAPS + 1
    )
    if (
        (has_used_multiple_compounds or is_wet or no_pit_window)
        and remaining_laps <= fitted_usable_life
    ):
        candidates.append(StrategyCandidate(label="No stop", stops=[]))

    available_compounds = _get_available_compounds(player.tyre_sets, snapshot.weather)
    logger.info(
        "candidate_generator: available_compounds=%s (from %d tyre_sets, weather=%d)",
        available_compounds, len(player.tyre_sets), snapshot.weather,
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
    last_pit_lap_exclusive = snapshot.total_laps - MIN_FINAL_STINT_LAPS + 1
    for compound, name in available_compounds.items():
        if compound == current_compound and not has_used_multiple_compounds and not is_wet:
            # Would violate two-compound rule unless we've already used another
            # (rule is waived in wet conditions).
            continue
        for pit_lap in range(
            snapshot.current_lap + MIN_STINT_LAPS,
            last_pit_lap_exclusive,
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
                # Ensure two-compound rule is met across all stints (waived in wet)
                compounds_used = {current_compound, c1_compound, c2_compound}
                if not has_used_multiple_compounds and not is_wet and len(compounds_used) < 2:
                    continue
                for lap1 in range(
                    snapshot.current_lap + MIN_STINT_LAPS,
                    snapshot.total_laps - MIN_STINT_LAPS - 1,
                    two_stop_step,
                ):
                    for lap2 in range(
                        lap1 + MIN_STINT_LAPS,
                        last_pit_lap_exclusive,
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

    # Lifespan pruning: drop candidates whose stints exceed compound lifespan.
    candidates = [
        c for c in candidates
        if _candidate_fits_lifespans(
            c,
            starting_compound=current_compound,
            starting_tyre_age=player.tyre_age_laps,
            current_lap=snapshot.current_lap,
            total_laps=snapshot.total_laps,
            coefficients=coefficients,
        )
    ]

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

    # When the front wing is damaged, repair it at the first stop of each
    # candidate (you fix the wing while already stopped). The existing candidates
    # span early->late first-pit laps, so this yields repair-early through
    # repair-late; the 0-stop option is the never-repair baseline.
    if player.front_wing_damage >= REPAIR_DAMAGE_THRESHOLD:
        for candidate in final:
            if candidate.stops:
                candidate.stops[0].repair_front_wing = True
                candidate.label = f"{candidate.label} +FW"

    return final


def _find_player(cars: list[CarSnapshot], index: int) -> CarSnapshot | None:
    for car in cars:
        if car.car_index == index:
            return car
    return None


def _get_available_compounds(
    tyre_sets: list[TyreSetInfo], weather: int
) -> dict[int, str]:
    """Returns dict of compound code -> display name for available, non-fitted compounds
    that pass the lap delta threshold and match current weather conditions. Falls back
    to standard dry/wet compounds when tyre set data is not yet available (early laps)."""
    is_wet = weather >= WET_WEATHER_THRESHOLD
    if not tyre_sets:
        return {INTERMEDIATE: "I"} if is_wet else {SOFT: "S", MEDIUM: "M", HARD: "H"}
    compounds: dict[int, str] = {}
    for ts in tyre_sets:
        if not ts.available or ts.fitted:
            continue
        if abs(ts.lap_delta_time) > LAP_DELTA_THRESHOLD_MS:
            continue
        compound = ts.visual_tyre_compound
        if is_wet and compound in DRY_COMPOUNDS:
            continue
        if not is_wet and compound in WET_COMPOUNDS:
            continue
        if compound not in compounds:
            compounds[compound] = COMPOUND_NAMES.get(compound, f"C{compound}")
    return compounds


def _compute_lap_step(remaining_laps: int) -> int:
    """Compute lap increment for pit windows to keep candidate count manageable."""
    if remaining_laps <= 7:
        return 1
    if remaining_laps <= 15:
        return 2
    if remaining_laps <= 30:
        return 3
    return 5


def _candidate_fits_lifespans(
    candidate: StrategyCandidate,
    starting_compound: int,
    starting_tyre_age: int,
    current_lap: int,
    total_laps: int,
    coefficients=None,
) -> bool:
    """Return True if every stint in the candidate fits within its compound's lifespan.

    Stint length conventions:
    - First stint: from current_lap up to (but not including) the first pit lap,
      plus the laps already accumulated on the fitted tyre (starting_tyre_age).
    - Middle stints: from previous pit_lap to next pit_lap.
    - Final stint: from last pit_lap through total_laps inclusive.
    """
    stint_start = current_lap
    stint_compound = starting_compound
    age_offset = starting_tyre_age

    for stop in candidate.stops:
        stint_len = (stop.on_lap - stint_start) + age_offset
        if stint_len > stint_cap_laps(stint_compound, coefficients, "PLAYER"):
            return False
        stint_start = stop.on_lap
        stint_compound = stop.new_compound
        age_offset = 0  # fresh tyre after pit

    final_stint_len = (total_laps - stint_start + 1) + age_offset
    if final_stint_len > stint_cap_laps(stint_compound, coefficients, "PLAYER"):
        return False

    return True
