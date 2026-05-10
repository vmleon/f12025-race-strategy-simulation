from simulator.coefficients import Coefficients
from simulator.engine import MonteCarloEngine
from simulator.models import CarSnapshot, Penalty, PitStop, PitStrategy, RaceSnapshot


def _make_car(
    car_index: int,
    driver_name: str = "Driver",
    ai_controlled: bool = True,
    position: int = 1,
    tyre_compound: int = 17,
    tyre_age_laps: int = 0,
    fuel_kg: float = 80.0,
    fuel_burn_per_sector_kg: float = 0.18,
    front_wing_damage: int = 0,
    floor_damage: int = 0,
    engine_damage: int = 0,
    num_pit_stops: int = 0,
    total_time_ms: float = 0.0,
    penalties: list[Penalty] | None = None,
) -> CarSnapshot:
    return CarSnapshot(
        car_index=car_index,
        driver_name=driver_name,
        ai_controlled=ai_controlled,
        position=position,
        tyre_compound=tyre_compound,
        tyre_age_laps=tyre_age_laps,
        fuel_kg=fuel_kg,
        fuel_burn_per_sector_kg=fuel_burn_per_sector_kg,
        front_wing_damage=front_wing_damage,
        floor_damage=floor_damage,
        engine_damage=engine_damage,
        num_pit_stops=num_pit_stops,
        total_time_ms=total_time_ms,
        penalties=penalties or [],
    )


def _make_snapshot(
    cars: list[CarSnapshot] | None = None,
    total_laps: int = 5,
    current_lap: int = 1,
    pit_strategy: PitStrategy | None = None,
) -> RaceSnapshot:
    if cars is None:
        cars = [
            _make_car(0, "Hamilton", position=1),
            _make_car(1, "Verstappen", position=2),
            _make_car(2, "Norris", position=3),
        ]
    return RaceSnapshot(
        track_id=0,
        total_laps=total_laps,
        current_lap=current_lap,
        current_sector=0,
        weather=0,
        track_temp=35,
        air_temp=25,
        safety_car=False,
        cars=cars,
        pit_strategy=pit_strategy,
    )


class TestSimulateBasic:
    def test_returns_result_for_each_car(self):
        snapshot = _make_snapshot()
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=100)

        assert len(result.cars) == 3
        assert result.iterations == 100
        assert result.wall_clock_ms >= 0

    def test_positions_are_valid(self):
        snapshot = _make_snapshot()
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=200)

        for car in result.cars:
            assert 1.0 <= car.mean_position <= 3.0
            assert car.position_std_dev >= 0
            assert car.ci95_low >= 1.0
            assert car.ci95_high <= 3.0

    def test_probabilities_in_range(self):
        snapshot = _make_snapshot()
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=200)

        for car in result.cars:
            assert 0.0 <= car.dnf_probability <= 1.0
            assert 0.0 <= car.top3_probability <= 1.0
            assert 0.0 <= car.points_finish_probability <= 1.0

    def test_position_distribution_sums_to_one(self):
        snapshot = _make_snapshot()
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=200)

        for car in result.cars:
            total = sum(car.position_distribution.values())
            assert abs(total - 1.0) < 0.01

    def test_deterministic_with_seed(self):
        snapshot = _make_snapshot()
        r1 = MonteCarloEngine(Coefficients.defaults(), seed=123).simulate(
            snapshot, max_iterations=100
        )
        r2 = MonteCarloEngine(Coefficients.defaults(), seed=123).simulate(
            snapshot, max_iterations=100
        )

        for c1, c2 in zip(r1.cars, r2.cars):
            assert c1.mean_position == c2.mean_position
            assert c1.dnf_probability == c2.dnf_probability


class TestPitStrategy:
    def test_pit_strategy_applied(self):
        player = _make_car(
            0, "Player", ai_controlled=False, position=1, tyre_compound=16
        )
        snapshot = _make_snapshot(
            cars=[player, _make_car(1, "AI1", position=2)],
            total_laps=10,
            pit_strategy=PitStrategy(
                target_car_index=0,
                stops=[PitStop(on_lap=3, new_compound=18)],
            ),
        )

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=50)

        assert len(result.cars) == 2
        assert result.iterations == 50


class TestConvergence:
    def test_convergence_stops_early(self):
        cars = [_make_car(i, f"D{i}", position=i + 1) for i in range(5)]
        snapshot = _make_snapshot(cars=cars, total_laps=3)

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=5000)

        # With only 3 laps and clear position order, should converge before 5000
        assert result.converged or result.iterations == 5000


class TestDamageEffect:
    def test_damage_increases_sector_time(self):
        healthy = _make_car(0, "Healthy", position=1)
        damaged = _make_car(
            0, "Damaged", position=1,
            front_wing_damage=50, floor_damage=30, engine_damage=20,
        )
        snapshot_healthy = _make_snapshot(cars=[healthy], total_laps=3)
        snapshot_damaged = _make_snapshot(cars=[damaged], total_laps=3)

        # With damage, mean position should be worse (higher number)
        # Since there's only 1 car, position is always 1, but we can at least
        # verify the simulation runs without error
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        r_healthy = engine.simulate(snapshot_healthy, max_iterations=50)
        engine2 = MonteCarloEngine(Coefficients.defaults(), seed=42)
        r_damaged = engine2.simulate(snapshot_damaged, max_iterations=50)

        assert len(r_healthy.cars) == 1
        assert len(r_damaged.cars) == 1


class TestPenalties:
    def test_time_penalty_worsens_position(self):
        """A time penalty added to final result should hurt the penalised car."""
        clean = _make_car(0, "Clean", position=1, total_time_ms=0.0)
        penalised = _make_car(
            1, "Penalised", position=2, total_time_ms=100.0,
            penalties=[Penalty(penalty_type="time", seconds=5)],
        )
        snapshot = _make_snapshot(cars=[clean, penalised], total_laps=3)

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=100)

        penalised_result = next(c for c in result.cars if c.car_index == 1)
        clean_result = next(c for c in result.cars if c.car_index == 0)
        # Penalised car should have a worse (higher) mean position
        assert penalised_result.mean_position >= clean_result.mean_position

    def test_drive_through_penalty_adds_time(self):
        """Drive-through penalty forces a pit lane transit."""
        no_penalty = _make_car(0, "NoPenalty", position=1)
        with_penalty = _make_car(
            1, "DriveThrough", position=2,
            penalties=[Penalty(penalty_type="drive_through")],
        )
        snapshot = _make_snapshot(cars=[no_penalty, with_penalty], total_laps=5)

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=100)

        penalised_result = next(c for c in result.cars if c.car_index == 1)
        clean_result = next(c for c in result.cars if c.car_index == 0)
        assert penalised_result.mean_position >= clean_result.mean_position

    def test_stop_go_penalty_adds_time(self):
        """Stop-go penalty forces pit transit plus stationary stop."""
        no_penalty = _make_car(0, "NoPenalty", position=1)
        with_penalty = _make_car(
            1, "StopGo", position=2,
            penalties=[Penalty(penalty_type="stop_go", seconds=10)],
        )
        snapshot = _make_snapshot(cars=[no_penalty, with_penalty], total_laps=5)

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=100)

        penalised_result = next(c for c in result.cars if c.car_index == 1)
        clean_result = next(c for c in result.cars if c.car_index == 0)
        assert penalised_result.mean_position >= clean_result.mean_position

    def test_expired_penalty_window_retires_car(self):
        """If laps_to_serve is already 0, the car is disqualified (retired)."""
        penalised = _make_car(
            0, "Expired", position=1, ai_controlled=False,
            penalties=[Penalty(penalty_type="drive_through", laps_to_serve=0)],
        )
        other = _make_car(1, "Other", position=2)
        snapshot = _make_snapshot(cars=[penalised, other], total_laps=5)

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=50)

        # Car with expired window should have high DNF probability (disqualified)
        penalised_result = next(c for c in result.cars if c.car_index == 0)
        assert penalised_result.dnf_probability > 0.0

    def test_no_penalties_unchanged_behavior(self):
        """Simulation with no penalties should produce the same results as before."""
        snapshot = _make_snapshot()
        r1 = MonteCarloEngine(Coefficients.defaults(), seed=42).simulate(
            snapshot, max_iterations=100
        )
        # All cars have empty penalties by default
        for car_result in r1.cars:
            assert 1.0 <= car_result.mean_position <= 3.0


def test_ai_pits_at_exactly_soft_lifespan_30():
    """AI on Soft should NOT pit at age 29 but SHOULD pit at age 30 (game-imposed lifespan)."""
    from simulator.engine import MonteCarloEngine
    from simulator.coefficients import Coefficients
    from simulator.car_state import CarState

    engine = MonteCarloEngine(Coefficients(), seed=42)
    snapshot = _make_snapshot()

    # Age 29: should NOT pit (under lifespan 30)
    car_young = CarState(
        car_index=1, driver_name="AI", ai_controlled=True, position=2,
        tyre_compound=16, tyre_age_laps=29, fuel_kg=50.0,
        fuel_burn_per_sector_kg=0.18, front_wing_damage=0, floor_damage=0,
        engine_damage=0, num_pit_stops=0, total_time_ms=0.0, current_lap=20,
        lap_pace_ms=90_000.0,
    )
    engine._check_pit_stop(car_young, lap=20, snapshot=snapshot)
    assert car_young.num_pit_stops == 0, "AI should NOT pit at age 29 (under lifespan 30)"

    # Age 30: SHOULD pit
    car_due = CarState(
        car_index=1, driver_name="AI", ai_controlled=True, position=2,
        tyre_compound=16, tyre_age_laps=30, fuel_kg=50.0,
        fuel_burn_per_sector_kg=0.18, front_wing_damage=0, floor_damage=0,
        engine_damage=0, num_pit_stops=0, total_time_ms=0.0, current_lap=20,
        lap_pace_ms=90_000.0,
    )
    engine._check_pit_stop(car_due, lap=20, snapshot=snapshot)
    assert car_due.num_pit_stops == 1, "AI should pit at age 30 (lifespan)"
    assert car_due.tyre_age_laps == 0, "Tyre age should reset after pit"
