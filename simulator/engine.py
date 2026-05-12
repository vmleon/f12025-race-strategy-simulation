from __future__ import annotations

import math
import time
from random import Random

import numpy as np

from simulator.car_state import CarState
from simulator.coefficients import Coefficients
from simulator.models import Penalty
from simulator.tyre_lifespan import compound_lifespan
from simulator.models import CarResult, RaceSnapshot, SimulationResult

DEFAULT_ITERATIONS = 1_000
MAX_ITERATIONS = 10_000
CONVERGENCE_THRESHOLD = 0.1  # positions
CONVERGENCE_CHECK_INTERVAL = 200
SECTORS_PER_LAP = 3

PIT_STOP_TIME_MS = 22_000.0
PIT_LANE_TRANSIT_TIME_MS = 20_000.0  # drive-through cost (no tyre change)
BASE_DNF_RATE_PER_SECTOR = 0.0001

# Option-C constants: replace coefficients we no longer calibrate.
NOISE_SIGMA_MS = 150.0          # per-sector pace noise (was: residual_variance)
OVERTAKE_PROB_DEFAULT = 0.30    # was: overtake_probability per regime/sector


class MonteCarloEngine:
    """Monte Carlo race strategy simulation engine.

    Runs N iterations of the remaining race at per-sector granularity,
    producing position probability distributions for each car.
    """

    def __init__(self, coefficients: Coefficients, seed: int | None = None) -> None:
        self.coefficients = coefficients
        self.rng = Random(seed)

    def simulate(
        self, snapshot: RaceSnapshot, max_iterations: int = DEFAULT_ITERATIONS
    ) -> SimulationResult:
        num_cars = len(snapshot.cars)
        start = time.monotonic()

        # position_counts row i is attributed to snapshot.cars[i] in _build_results.
        # Must lookup via car_index because _run_iteration sorts the cars list in
        # place at every sector — `enumerate(cars)` no longer matches snapshot order.
        # The bug behind the persistent P19 projection lived here.
        car_index_to_row = {cs.car_index: i for i, cs in enumerate(snapshot.cars)}
        position_counts = np.zeros((num_cars, num_cars), dtype=np.int32)
        dnf_counts = np.zeros(num_cars, dtype=np.int32)

        prev_means: np.ndarray | None = None
        converged = False
        iterations = 0

        for _ in range(max_iterations):
            iterations += 1
            cars = self._init_cars(snapshot)
            self._run_iteration(cars, snapshot)
            self._record_results(cars, position_counts, dnf_counts, car_index_to_row)

            # Convergence check
            if (
                iterations % CONVERGENCE_CHECK_INTERVAL == 0
                and iterations >= CONVERGENCE_CHECK_INTERVAL * 2
            ):
                means = self._compute_means(position_counts, iterations)
                if prev_means is not None and np.all(
                    np.abs(means - prev_means) < CONVERGENCE_THRESHOLD
                ):
                    converged = True
                    break
                prev_means = means.copy()

        wall_clock_ms = int((time.monotonic() - start) * 1000)
        results = self._build_results(snapshot, position_counts, dnf_counts, iterations)
        return SimulationResult(
            iterations=iterations,
            converged=converged,
            wall_clock_ms=wall_clock_ms,
            cars=results,
        )

    # ── iteration ────────────────────────────────────────────────────────

    def _init_cars(self, snapshot: RaceSnapshot) -> list[CarState]:
        return [
            CarState.from_snapshot(cs, snapshot.current_lap, snapshot.track_id)
            for cs in snapshot.cars
        ]

    def _run_iteration(self, cars: list[CarState], snapshot: RaceSnapshot) -> None:
        safety_car = snapshot.safety_car
        start_sector = snapshot.current_sector

        for lap in range(snapshot.current_lap, snapshot.total_laps + 1):
            sector_start = start_sector if lap == snapshot.current_lap else 0

            for sector in range(sector_start, SECTORS_PER_LAP):
                # Safety car / red flag events (checked once per lap at sector 0)
                if sector == 0:
                    safety_car = self._check_safety_car_event(safety_car, cars)
                    if self._check_red_flag():
                        self._handle_red_flag(cars)

                # Process each car in position order
                cars.sort(key=lambda c: c.position)

                for car in cars:
                    if car.retired:
                        continue

                    if sector == 0:
                        self._process_penalties(car)
                        if car.retired:
                            car.position = len(cars)
                            continue
                        self._check_pit_stop(car, lap, snapshot)

                    sector_time = self._predict_sector_time(
                        car, sector, safety_car, cars
                    )
                    car.total_time_ms += sector_time

                    self._consume_fuel(car)
                    self._age_tyres(car, sector)

                    if self._check_dnf(car):
                        car.retired = True
                        car.position = len(cars)

                # Resolve overtakes after all cars have run the sector
                self._resolve_overtakes(cars, sector)

            # Increment lap counter
            for car in cars:
                if not car.retired:
                    car.current_lap = lap + 1

        self._apply_time_penalties(cars)
        self._assign_final_positions(cars)

    # ── sector time prediction (Option-C additive model) ────────────────
    # Terms: per-car pace + tyre_deg + fuel_effect + Gaussian noise.
    # Damage / dirty air / DRS were dropped — no calibrated knobs feed them
    # under Option C and the cold-start defaults were generating noise.

    def _predict_sector_time(
        self,
        car: CarState,
        sector: int,
        safety_car: bool,
        cars: list[CarState],  # kept for signature stability; unused under Option C
    ) -> float:
        regime = car.regime

        # Per-car base sector pace, derived from observed lap times (median of
        # last 3) by car_state. Splits the lap evenly across 3 sectors —
        # per-sector pace ratios are not yet calibrated.
        base_sector_pace = car.lap_pace_ms / SECTORS_PER_LAP

        if safety_car:
            return base_sector_pace * 1.4

        noise = self.rng.gauss(0, NOISE_SIGMA_MS)
        tyre_deg = self._tyre_degradation(car, regime)
        fuel_effect = self.coefficients.get("fuel_effect", regime, sector) * car.fuel_kg

        sector_time = base_sector_pace + tyre_deg + fuel_effect + noise
        return max(sector_time, base_sector_pace * 0.9)

    def _tyre_degradation(self, car: CarState, regime: str) -> float:
        knob = {
            16: "tyre_deg_soft",
            17: "tyre_deg_medium",
            18: "tyre_deg_hard",
            7: "tyre_deg_intermediate",
            8: "tyre_deg_wet",
        }.get(car.tyre_compound, "tyre_deg_medium")
        deg_per_lap = self.coefficients.get(knob, regime)
        return deg_per_lap * car.tyre_age_laps

    # ── sub-models ───────────────────────────────────────────────────────

    @staticmethod
    def _consume_fuel(car: CarState) -> None:
        car.fuel_kg = max(0.0, car.fuel_kg - car.fuel_burn_per_sector_kg)

    @staticmethod
    def _age_tyres(car: CarState, sector: int) -> None:
        if sector == 2:
            car.tyre_age_laps += 1

    # ── pit stop ─────────────────────────────────────────────────────────

    def _check_pit_stop(
        self, car: CarState, lap: int, snapshot: RaceSnapshot
    ) -> None:
        # Player strategy
        if (
            snapshot.pit_strategy is not None
            and car.car_index == snapshot.pit_strategy.target_car_index
        ):
            for stop in snapshot.pit_strategy.stops:
                if stop.on_lap == lap:
                    self._execute_pit_stop(car, stop.new_compound)
                    return

        # AI heuristic: pit at the game-imposed compound lifespan (the cliff).
        if car.ai_controlled:
            if car.tyre_age_laps >= compound_lifespan(car.tyre_compound):
                new_compound = 18 if car.tyre_compound == 16 else 17
                self._execute_pit_stop(car, new_compound)

    def _execute_pit_stop(self, car: CarState, new_compound: int) -> None:
        pit_mean = self.coefficients.get("pit_stop_time_loss", car.regime)
        if pit_mean <= 0:
            pit_mean = PIT_STOP_TIME_MS
        pit_sd = self.coefficients.get("pit_stop_time_loss_stddev", car.regime)
        if pit_sd > 0:
            pit_time = self.rng.gauss(pit_mean, pit_sd)
            pit_time = max(pit_time, pit_mean * 0.5)
        else:
            pit_time = pit_mean
        car.total_time_ms += pit_time
        car.tyre_compound = new_compound
        car.tyre_age_laps = 0
        car.num_pit_stops += 1

    # ── penalty handling ────────────────────────────────────────────────

    def _process_penalties(self, car: CarState) -> None:
        """Process pending drive-through / stop-go penalties at sector 0."""
        if not car.pending_penalties:
            return
        remaining: list = []
        for penalty in car.pending_penalties:
            if penalty.laps_to_serve <= 0:
                # Window expired — disqualified
                car.retired = True
                return

            if car.ai_controlled or penalty.laps_to_serve == 1:
                # AI serves immediately; anyone serves on last lap of window
                self._serve_penalty(car, penalty)
            else:
                # Decrement window, keep pending
                remaining.append(penalty.model_copy(
                    update={"laps_to_serve": penalty.laps_to_serve - 1}
                ))
        car.pending_penalties = remaining

    def _serve_penalty(self, car: CarState, penalty: Penalty) -> None:
        pit_transit = self.coefficients.get("pit_stop_time_loss", car.regime)
        if pit_transit <= 0:
            pit_transit = PIT_LANE_TRANSIT_TIME_MS

        if penalty.penalty_type == "drive_through":
            car.total_time_ms += pit_transit
        elif penalty.penalty_type == "stop_go":
            car.total_time_ms += pit_transit + penalty.seconds * 1000.0

    @staticmethod
    def _apply_time_penalties(cars: list[CarState]) -> None:
        for car in cars:
            if not car.retired and car.penalty_time_ms > 0:
                car.total_time_ms += car.penalty_time_ms

    # ── overtake resolution ──────────────────────────────────────────────

    def _resolve_overtakes(self, cars: list[CarState], sector: int) -> None:
        active = sorted(
            (c for c in cars if not c.retired), key=lambda c: c.total_time_ms
        )
        for i, car in enumerate(active):
            expected_pos = i + 1
            if car.position != expected_pos:
                defender = self._find_car_at_position(cars, expected_pos)
                if defender is not None and not defender.retired:
                    pace_delta_ms = defender.total_time_ms - car.total_time_ms
                    overtake_prob = self._overtake_probability(
                        car, pace_delta_ms, sector
                    )
                    if self.rng.random() < overtake_prob:
                        old_pos = car.position
                        car.position = defender.position
                        defender.position = old_pos

    def _overtake_probability(
        self, car: CarState, pace_delta_ms: float, sector: int
    ) -> float:
        # Option C: flat default — calibration of overtake_probability requires
        # data we don't currently fit. _sigmoid(pace_delta) keeps the shape so
        # faster cars still pass more often than slower ones.
        x = pace_delta_ms / 500.0
        return OVERTAKE_PROB_DEFAULT * _sigmoid(x)

    @staticmethod
    def _find_car_at_position(
        cars: list[CarState], position: int
    ) -> CarState | None:
        for car in cars:
            if car.position == position and not car.retired:
                return car
        return None

    # ── safety car / red flag ────────────────────────────────────────────

    def _check_safety_car_event(
        self, current_safety_car: bool, cars: list[CarState]
    ) -> bool:
        # Option C: don't randomly trigger safety cars. The simulator only
        # respects the `safety_car` flag that came in with the snapshot; if
        # that flag is true on entry we still resolve when it ends.
        if current_safety_car:
            # Safety car lasts 3-5 laps on average; 30% chance of ending each lap.
            return self.rng.random() > 0.30
        return False

    @staticmethod
    def _compress_field(cars: list[CarState]) -> None:
        active = sorted(
            (c for c in cars if not c.retired), key=lambda c: c.position
        )
        if not active:
            return
        leader_time = active[0].total_time_ms
        for i, car in enumerate(active[1:], 1):
            car.total_time_ms = leader_time + i * 500.0

    def _check_red_flag(self) -> bool:
        return self.rng.random() < 0.001

    def _handle_red_flag(self, cars: list[CarState]) -> None:
        for car in cars:
            if not car.retired:
                car.tyre_age_laps = 0
        self._compress_field(cars)

    # ── DNF ──────────────────────────────────────────────────────────────

    def _check_dnf(self, car: CarState) -> bool:
        # Option C: flat per-sector DNF rate. Damage→DNF coupling required
        # calibrated multipliers we don't fit; revisit when we have data.
        return self.rng.random() < BASE_DNF_RATE_PER_SECTOR

    # ── final positions ──────────────────────────────────────────────────

    @staticmethod
    def _assign_final_positions(cars: list[CarState]) -> None:
        active = [c for c in cars if not c.retired]
        retired = [c for c in cars if c.retired]

        active.sort(key=lambda c: c.total_time_ms)
        retired.sort(key=lambda c: c.current_lap, reverse=True)

        pos = 1
        for car in active:
            car.position = pos
            pos += 1
        for car in retired:
            car.position = pos
            pos += 1

    # ── result collection ────────────────────────────────────────────────

    @staticmethod
    def _record_results(
        cars: list[CarState],
        position_counts: np.ndarray,
        dnf_counts: np.ndarray,
        car_index_to_row: dict[int, int],
    ) -> None:
        for car in cars:
            row = car_index_to_row.get(car.car_index)
            if row is None:
                continue
            pos = car.position - 1  # 0-based
            if 0 <= pos < position_counts.shape[1]:
                position_counts[row, pos] += 1
            if car.retired:
                dnf_counts[row] += 1

    @staticmethod
    def _compute_means(position_counts: np.ndarray, iterations: int) -> np.ndarray:
        positions = np.arange(1, position_counts.shape[1] + 1)
        return (position_counts * positions).sum(axis=1) / iterations

    @staticmethod
    def _build_results(
        snapshot: RaceSnapshot,
        position_counts: np.ndarray,
        dnf_counts: np.ndarray,
        iterations: int,
    ) -> list[CarResult]:
        results = []
        positions = np.arange(1, position_counts.shape[1] + 1)

        for i, cs in enumerate(snapshot.cars):
            counts = position_counts[i]

            # Mean and standard deviation
            mean = float((counts * positions).sum() / iterations)
            variance = float((counts * (positions - mean) ** 2).sum() / iterations)
            std_dev = math.sqrt(variance)

            # 95% CI (2.5th and 97.5th percentiles)
            ci95_low = _percentile(counts, iterations, 0.025)
            ci95_high = _percentile(counts, iterations, 0.975)

            # DNF probability
            dnf_prob = float(dnf_counts[i]) / iterations

            # Top 3 and points finish probabilities
            probs = counts / iterations
            top3 = float(probs[:3].sum())
            points_finish = float(probs[:10].sum())

            # Position distribution
            distribution = {
                int(p): float(prob)
                for p, prob in zip(positions, probs)
                if prob > 0
            }

            results.append(
                CarResult(
                    car_index=cs.car_index,
                    driver_name=cs.driver_name,
                    mean_position=mean,
                    position_std_dev=std_dev,
                    ci95_low=ci95_low,
                    ci95_high=ci95_high,
                    dnf_probability=dnf_prob,
                    top3_probability=top3,
                    points_finish_probability=points_finish,
                    position_distribution=distribution,
                )
            )

        return results


# ── helpers ──────────────────────────────────────────────────────────────

def _sigmoid(x: float) -> float:
    x = max(-50.0, min(50.0, x))
    return 1.0 / (1.0 + math.exp(-x))


def _percentile(counts: np.ndarray, iterations: int, p: float) -> float:
    target = math.ceil(p * iterations)
    cumulative = 0
    for pos_idx in range(len(counts)):
        cumulative += int(counts[pos_idx])
        if cumulative >= target:
            return float(pos_idx + 1)
    return float(len(counts))
