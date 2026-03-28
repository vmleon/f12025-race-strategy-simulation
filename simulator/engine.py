from __future__ import annotations

import math
import time
from random import Random

import numpy as np

from simulator.car_state import CarState
from simulator.coefficients import Coefficients
from simulator.models import CarResult, RaceSnapshot, SimulationResult

DEFAULT_ITERATIONS = 1_000
MAX_ITERATIONS = 10_000
CONVERGENCE_THRESHOLD = 0.1  # positions
CONVERGENCE_CHECK_INTERVAL = 200
SECTORS_PER_LAP = 3

PIT_STOP_TIME_MS = 22_000.0
BASE_DNF_RATE_PER_SECTOR = 0.0001


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

        # position_counts[car_idx][position] where position is 0-based
        position_counts = np.zeros((num_cars, num_cars), dtype=np.int32)
        dnf_counts = np.zeros(num_cars, dtype=np.int32)

        prev_means: np.ndarray | None = None
        converged = False
        iterations = 0

        for _ in range(max_iterations):
            iterations += 1
            cars = self._init_cars(snapshot)
            self._run_iteration(cars, snapshot)
            self._record_results(cars, position_counts, dnf_counts)

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
            CarState.from_snapshot(cs, snapshot.current_lap)
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

        self._assign_final_positions(cars)

    # ── sector time prediction (additive model) ─────────────────────────

    def _predict_sector_time(
        self,
        car: CarState,
        sector: int,
        safety_car: bool,
        cars: list[CarState],
    ) -> float:
        regime = car.regime
        coeff = self.coefficients

        # Base pace
        base_pace = coeff.get("base_pace_mean", regime, sector)
        if base_pace <= 0:
            base_pace = 30_000.0

        # Safety car: everyone laps at ~40% slower pace
        if safety_car:
            return base_pace * 1.4

        # Residual noise (regression residual variance from calibration)
        variance = coeff.get("residual_variance", regime, sector)
        noise = (
            self.rng.gauss(0, math.sqrt(variance))
            if variance > 0
            else self.rng.gauss(0, 200)
        )

        # Tyre degradation
        tyre_deg = self._tyre_degradation(car, regime)

        # Fuel effect
        fuel_effect = coeff.get("fuel_effect", regime, sector) * car.fuel_kg

        # Damage effects
        damage_effect = self._damage_effect(car, regime, sector)

        # Dirty air effect
        dirty_air = self._dirty_air_effect(car, cars, regime, sector)

        # DRS effect
        drs = self._drs_effect(car, cars, sector, regime)

        sector_time = (
            base_pace + tyre_deg + fuel_effect + damage_effect + dirty_air + drs + noise
        )
        return max(sector_time, base_pace * 0.9)

    def _tyre_degradation(self, car: CarState, regime: str) -> float:
        knob = {16: "tyre_deg_soft", 17: "tyre_deg_medium", 18: "tyre_deg_hard"}.get(
            car.tyre_compound, "tyre_deg_medium"
        )
        deg_per_lap = self.coefficients.get(knob, regime)
        return deg_per_lap * car.tyre_age_laps

    def _damage_effect(self, car: CarState, regime: str, sector: int) -> float:
        coeff = self.coefficients
        front_wing = coeff.get("front_wing_damage", regime, sector) * car.front_wing_damage
        floor = coeff.get("floor_damage", regime, sector) * car.floor_damage
        engine = coeff.get("engine_damage", regime, sector) * car.engine_damage
        return front_wing + floor + engine

    def _dirty_air_effect(
        self, car: CarState, cars: list[CarState], regime: str, sector: int
    ) -> float:
        car_ahead = self._find_car_at_position(cars, car.position - 1)
        if car_ahead is None:
            return 0.0

        gap_ms = car.total_time_ms - car_ahead.total_time_ms
        if gap_ms > 2000:
            return 0.0

        dirty_air_coeff = self.coefficients.get("dirty_air", regime, sector)
        scale = 1.0 - (gap_ms / 2000.0)
        return dirty_air_coeff * max(0.0, scale)

    def _drs_effect(
        self, car: CarState, cars: list[CarState], sector: int, regime: str
    ) -> float:
        car_ahead = self._find_car_at_position(cars, car.position - 1)
        if car_ahead is None:
            return 0.0

        gap_ms = car.total_time_ms - car_ahead.total_time_ms
        if gap_ms > 1000:
            return 0.0

        return self.coefficients.get("drs_advantage", regime, sector)

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

        # AI heuristic: pit when tyres are very old
        if car.ai_controlled:
            threshold = {
                16: 15 + self.rng.randint(0, 5),   # soft: 15-20 laps
                17: 25 + self.rng.randint(0, 5),   # medium: 25-30 laps
                18: 35 + self.rng.randint(0, 5),   # hard: 35-40 laps
            }.get(car.tyre_compound, 25)
            if car.tyre_age_laps >= threshold:
                new_compound = 18 if car.tyre_compound == 16 else 17
                self._execute_pit_stop(car, new_compound)

    @staticmethod
    def _execute_pit_stop(car: CarState, new_compound: int) -> None:
        car.total_time_ms += PIT_STOP_TIME_MS
        car.tyre_compound = new_compound
        car.tyre_age_laps = 0
        car.num_pit_stops += 1

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
        base_prob = self.coefficients.get("overtake_probability", car.regime, sector)
        x = pace_delta_ms / 500.0
        return base_prob * _sigmoid(x)

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
        sc_rate = self.coefficients.get("safety_car_rate", "AI")
        if not current_safety_car:
            if self.rng.random() < sc_rate:
                self._compress_field(cars)
                return True
            return False
        else:
            # Safety car lasts 3-5 laps on average; 30% chance of ending each lap
            return self.rng.random() > 0.30

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
        damage_multiplier = (
            1.0
            + car.engine_damage * 0.05
            + car.floor_damage * 0.01
            + car.front_wing_damage * 0.005
        )
        dnf_rate = BASE_DNF_RATE_PER_SECTOR * damage_multiplier
        return self.rng.random() < dnf_rate

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
    ) -> None:
        for i, car in enumerate(cars):
            pos = car.position - 1  # 0-based
            if 0 <= pos < position_counts.shape[1]:
                position_counts[i, pos] += 1
            if car.retired:
                dnf_counts[i] += 1

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
    return 1.0 / (1.0 + math.exp(-x))


def _percentile(counts: np.ndarray, iterations: int, p: float) -> float:
    target = math.ceil(p * iterations)
    cumulative = 0
    for pos_idx in range(len(counts)):
        cumulative += int(counts[pos_idx])
        if cumulative >= target:
            return float(pos_idx + 1)
    return float(len(counts))
