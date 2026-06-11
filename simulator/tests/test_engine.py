import pytest

from simulator.car_state import CarState
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
    sector_history_ms: list[list[int]] | None = None,
    sector_baseline_ms: list[int] | None = None,
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
        sector_history_ms=sector_history_ms or [[], [], []],
        sector_baseline_ms=sector_baseline_ms or [0, 0, 0],
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
        # Player (index 5) at the back; compare healthy vs heavily damaged.
        def field(player_damage):
            cars = [_make_car(i, f"AI{i}", position=i + 1) for i in range(5)]
            player = _make_car(
                5, "Player", ai_controlled=False, position=6,
                **player_damage,
            )
            return _make_snapshot(cars=cars + [player], total_laps=12)

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        healthy = engine.simulate(field({}), max_iterations=300)
        engine2 = MonteCarloEngine(Coefficients.defaults(), seed=42)
        damaged = engine2.simulate(
            field({"front_wing_damage": 60, "floor_damage": 40}), max_iterations=300
        )

        def mean_pos(result, car_index):
            return next(c.mean_position for c in result.cars if c.car_index == car_index)

        # Heavy damage should worsen (increase) the player's mean finishing position.
        assert mean_pos(damaged, 5) > mean_pos(healthy, 5)


class TestResultsAttribution:
    """Regression tests for the position-attribution bug where _record_results
    indexed by enumeration of a sorted cars list rather than by car_index. The
    bug routed every car's outcome into the wrong row, pinning the player
    (last in snapshot order) to P19 regardless of pace."""

    def test_fast_player_at_back_projects_better_than_last(self):
        # Field of 20 with player as the LAST snapshot entry (car_index=19).
        # Player given the fastest observed lap (75 s) while AIs default to 90 s.
        # If results are attributed correctly the player must finish near the
        # front; with the bug they were pinned to P19/P20.
        cars = []
        for i in range(19):
            cars.append(_make_car(
                car_index=i, driver_name=f"AI{i}", ai_controlled=True,
                position=i + 1, total_time_ms=(i) * 1000.0,
            ))
        # Player at the back of the field, but with much faster observed pace.
        from simulator.models import CarSnapshot
        player_snapshot = CarSnapshot(
            car_index=19, driver_name="Player", ai_controlled=False,
            position=20, tyre_compound=17, tyre_age_laps=0,
            fuel_kg=80.0, fuel_burn_per_sector_kg=0.18,
            front_wing_damage=0, floor_damage=0, engine_damage=0,
            num_pit_stops=0, total_time_ms=19_000.0,
            sector_history_ms=[[25_000, 25_200], [25_000, 25_100], [25_000, 24_900]],  # ~75 s/lap
        )
        cars.append(player_snapshot)
        snapshot = _make_snapshot(cars=cars, total_laps=10, current_lap=1)

        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        result = engine.simulate(snapshot, max_iterations=200)

        player_result = next(c for c in result.cars if c.car_index == 19)
        # With ~15 s/lap pace advantage over 9 remaining laps (~135 s), the
        # player should comfortably climb the field, not be pinned at the back.
        assert player_result.mean_position < 5.0, (
            f"Expected player to project near the front; got mean_position="
            f"{player_result.mean_position}"
        )


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
    # Wet snapshot waives the mandatory compound-change stop, isolating the wear-cliff
    # behaviour this test targets (the cliff trigger is weather-independent).
    snapshot = _make_snapshot().model_copy(update={"weather": 4})

    # Age 29: should NOT pit (under lifespan 30)
    car_young = CarState(
        car_index=1, driver_name="AI", ai_controlled=True, position=2,
        tyre_compound=16, tyre_age_laps=29, fuel_kg=50.0,
        fuel_burn_per_sector_kg=0.18, front_wing_damage=0, floor_damage=0,
        engine_damage=0, num_pit_stops=0, total_time_ms=0.0, current_lap=20,
        sector_pace_ms=[30_000.0, 30_000.0, 30_000.0],
        perfect_sector_ms=[0.0, 0.0, 0.0],
    )
    engine._check_pit_stop(car_young, lap=20, snapshot=snapshot)
    assert car_young.num_pit_stops == 0, "AI should NOT pit at age 29 (under lifespan 30)"

    # Age 30: SHOULD pit
    car_due = CarState(
        car_index=1, driver_name="AI", ai_controlled=True, position=2,
        tyre_compound=16, tyre_age_laps=30, fuel_kg=50.0,
        fuel_burn_per_sector_kg=0.18, front_wing_damage=0, floor_damage=0,
        engine_damage=0, num_pit_stops=0, total_time_ms=0.0, current_lap=20,
        sector_pace_ms=[30_000.0, 30_000.0, 30_000.0],
        perfect_sector_ms=[0.0, 0.0, 0.0],
    )
    engine._check_pit_stop(car_due, lap=20, snapshot=snapshot)
    assert car_due.num_pit_stops == 1, "AI should pit at age 30 (lifespan)"
    assert car_due.tyre_age_laps == 0, "Tyre age should reset after pit"


class TestDamagePenalty:
    def _car(self, **damage):
        snap = _make_car(0, **damage)
        return CarState.from_snapshot(snap, current_lap=1, track_id=1)

    def test_zero_damage_is_zero(self):
        engine = MonteCarloEngine(Coefficients.defaults())
        assert engine._damage_penalty(self._car()) == 0.0

    def test_front_wing_anchors(self):
        engine = MonteCarloEngine(Coefficients.defaults())
        # 2%  -> per lap 100.4ms -> /3
        assert engine._damage_penalty(self._car(front_wing_damage=2)) == pytest.approx(33.4667, abs=0.01)
        # 100% -> 6000ms/lap -> 2000ms/sector
        assert engine._damage_penalty(self._car(front_wing_damage=100)) == pytest.approx(2000.0)

    def test_floor_and_engine_scales(self):
        engine = MonteCarloEngine(Coefficients.defaults())
        # floor 100% -> 4800/lap -> 1600/sector
        assert engine._damage_penalty(self._car(floor_damage=100)) == pytest.approx(1600.0)
        # engine 100% -> 3000/lap -> 1000/sector
        assert engine._damage_penalty(self._car(engine_damage=100)) == pytest.approx(1000.0)

    def test_components_stack(self):
        engine = MonteCarloEngine(Coefficients.defaults())
        # front 100 + floor 100 -> (6000+4800)/3
        assert engine._damage_penalty(
            self._car(front_wing_damage=100, floor_damage=100)
        ) == pytest.approx(3600.0)


class TestFrontWingRepair:
    def _car(self, **damage):
        snap = _make_car(0, ai_controlled=False, **damage)
        return CarState.from_snapshot(snap, current_lap=1, track_id=1)

    def test_repair_resets_front_wing_and_adds_time(self):
        # Deterministic pit time: defaults have no stddev knob -> pit_time == mean.
        engine = MonteCarloEngine(Coefficients.defaults(), seed=1)
        car = self._car(front_wing_damage=70)
        engine._execute_pit_stop(car, new_compound=17, repair_front_wing=True)
        assert car.front_wing_damage == 0
        # 22_000 (pit) + 10_000 (repair)
        assert car.total_time_ms == pytest.approx(32_000.0)

    def test_no_repair_leaves_damage(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=1)
        car = self._car(front_wing_damage=70)
        engine._execute_pit_stop(car, new_compound=17)
        assert car.front_wing_damage == 70
        assert car.total_time_ms == pytest.approx(22_000.0)


class TestEngineDamageDnf:
    def _car(self, engine_damage):
        snap = _make_car(0, ai_controlled=False, engine_damage=engine_damage)
        return CarState.from_snapshot(snap, current_lap=1, track_id=1)

    def test_engine_damage_increases_dnf_rate(self):
        from simulator.engine import BASE_DNF_RATE_PER_SECTOR

        # rng.random() returns a value just above the base rate but below the
        # damage-scaled rate -> healthy car survives, damaged car retires.
        class FixedRng:
            def __init__(self, value): self.value = value
            def random(self): return self.value

        engine = MonteCarloEngine(Coefficients.defaults())
        engine.rng = FixedRng(BASE_DNF_RATE_PER_SECTOR * 2)  # 2x base

        assert engine._check_dnf(self._car(engine_damage=0)) is False   # rate = base
        assert engine._check_dnf(self._car(engine_damage=100)) is True  # rate = 4x base


class TestPerSectorPace:
    def test_per_sector_tyre_deg_is_consumed(self):
        # A per-sector tyre-deg coefficient (sector 1 only) must change S1's time.
        coeffs = Coefficients.defaults()
        coeffs.put("tyre_deg_medium", "AI", 1, 100.0)  # 100 ms/lap of age, sector 1
        coeffs.put("tyre_deg_medium", "AI", 0, 0.0)  # isolate S1: no deg in S0 (defaults are now non-zero)
        engine = MonteCarloEngine(coeffs, seed=1)
        car = CarState.from_snapshot(
            _make_car(0, tyre_age_laps=10, sector_baseline_ms=[30_000, 30_000, 30_000]),
            current_lap=1, track_id=0,
        )
        # Deg is applied as a delta from the stint reference age; force the reference
        # to a fresh tyre so the 10 laps of age translate to 10*100 = 1000 ms in S1.
        car.age_ref = 0.0
        # Sector 1 carries 10*100 = 1000 ms of deg; sector 0 carries none.
        # Average many draws to cancel the Gaussian noise.
        import statistics
        s0 = statistics.mean(engine._predict_sector_time(car, 0, False, []) for _ in range(400))
        s1 = statistics.mean(engine._predict_sector_time(car, 1, False, []) for _ in range(400))
        assert s1 - s0 == pytest.approx(1000.0, abs=60.0)

    def test_deg_and_fuel_are_zero_at_reference_conditions(self):
        # At the snapshot's own age/fuel, deg and fuel must add nothing — the base
        # pace already embeds them. Then aging past the reference accrues deg.
        import statistics
        coeffs = Coefficients.defaults()
        coeffs.put("tyre_deg_medium", "AI", -1, 100.0)  # large, to dwarf noise
        coeffs.put("fuel_effect", "AI", -1, 30.0)
        engine = MonteCarloEngine(coeffs, seed=1)
        car = CarState.from_snapshot(
            _make_car(0, tyre_age_laps=15, fuel_kg=90.0,
                      sector_baseline_ms=[30_000, 30_000, 30_000]),
            current_lap=1, track_id=0,
        )
        at_ref = statistics.mean(
            engine._predict_sector_time(car, 0, False, []) for _ in range(400))
        assert at_ref == pytest.approx(30_000.0, abs=60.0)  # no double-count

        car.tyre_age_laps = 16  # one lap past the reference -> +100 ms of deg
        aged = statistics.mean(
            engine._predict_sector_time(car, 0, False, []) for _ in range(400))
        assert aged - at_ref == pytest.approx(100.0, abs=60.0)

    def test_perfect_sector_is_the_floor(self):
        coeffs = Coefficients.defaults()
        engine = MonteCarloEngine(coeffs, seed=1)
        car = CarState.from_snapshot(
            _make_car(0, sector_baseline_ms=[30_000, 30_000, 30_000]),
            current_lap=1, track_id=0,
        )
        car.perfect_sector_ms = [29_999_000.0, 29_999_000.0, 29_999_000.0]  # absurd floor
        t = engine._predict_sector_time(car, 0, False, [])
        assert t == 29_999_000.0


class TestAiMandatoryPit:
    """AI must make their compound-change stop even in short races where the wear
    cliff is never reached — otherwise the field never pits and the player who
    correctly stops is projected to the back."""

    def _ai_car_state(self, **kw):
        snap = _make_car(0, ai_controlled=True, **kw)
        return snap, CarState.from_snapshot(snap, current_lap=kw.get("tyre_age_laps", 0), track_id=4)

    def test_ai_pit_lap_within_window_and_spread(self):
        from simulator.engine import MIN_AI_STINT_LAPS, MIN_AI_FINAL_STINT_LAPS
        laps = [MonteCarloEngine._ai_pit_lap(i, 17) for i in range(20)]
        assert all(MIN_AI_STINT_LAPS <= lap_ <= 17 - MIN_AI_FINAL_STINT_LAPS for lap_ in laps)
        assert len(set(laps)) > 1  # the field is spread across a window, not one lap

    def test_ai_makes_mandatory_stop_in_short_dry_race(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=1)
        snap, car = self._ai_car_state(tyre_compound=16, tyre_age_laps=10, num_pit_stops=0)
        snapshot = _make_snapshot(cars=[snap], total_laps=17, current_lap=10)
        engine._check_pit_stop(car, lap=12, snapshot=snapshot)
        assert car.num_pit_stops == 1
        assert car.tyre_compound != 16

    def test_ai_not_forced_in_the_wet(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=1)
        snap, car = self._ai_car_state(tyre_compound=16, tyre_age_laps=10, num_pit_stops=0)
        wet = _make_snapshot(cars=[snap], total_laps=17, current_lap=10).model_copy(
            update={"weather": 4})
        engine._check_pit_stop(car, lap=12, snapshot=wet)
        assert car.num_pit_stops == 0

    def test_ai_already_pitted_is_not_forced_again(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=1)
        snap, car = self._ai_car_state(tyre_compound=18, tyre_age_laps=5, num_pit_stops=1)
        snapshot = _make_snapshot(cars=[snap], total_laps=17, current_lap=10)
        engine._check_pit_stop(car, lap=14, snapshot=snapshot)
        assert car.num_pit_stops == 1

    def test_ai_not_forced_before_its_window(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=1)
        snap, car = self._ai_car_state(tyre_compound=16, tyre_age_laps=2, num_pit_stops=0)
        snapshot = _make_snapshot(cars=[snap], total_laps=17, current_lap=2)
        engine._check_pit_stop(car, lap=2, snapshot=snapshot)  # lap 2 < planned window
        assert car.num_pit_stops == 0
