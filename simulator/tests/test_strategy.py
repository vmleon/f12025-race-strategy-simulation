from simulator.coefficients import Coefficients
from simulator.engine import MonteCarloEngine
from simulator.models import CarSnapshot, PitStop, RaceSnapshot, StrategyCandidate, TyreSetInfo
from simulator.strategy import (
    StrategyEvaluator,
    TyreSet,
    check_feasibility,
    expected_points,
)


def _make_car(
    car_index: int,
    driver_name: str = "Driver",
    ai_controlled: bool = True,
    position: int = 1,
    tyre_compound: int = 16,
) -> CarSnapshot:
    return CarSnapshot(
        car_index=car_index,
        driver_name=driver_name,
        ai_controlled=ai_controlled,
        position=position,
        tyre_compound=tyre_compound,
        tyre_age_laps=0,
        fuel_kg=80.0,
        fuel_burn_per_sector_kg=0.18,
        front_wing_damage=0,
        floor_damage=0,
        engine_damage=0,
        num_pit_stops=0,
        total_time_ms=0.0,
    )


def _make_snapshot() -> RaceSnapshot:
    return RaceSnapshot(
        track_id=0,
        total_laps=10,
        current_lap=1,
        current_sector=0,
        weather=0,
        track_temp=35,
        air_temp=25,
        safety_car=False,
        cars=[
            _make_car(0, "Player", ai_controlled=False, position=1),
            _make_car(1, "AI1", position=2),
            _make_car(2, "AI2", position=3),
        ],
        pit_strategy=None,
    )


class TestStrategyEvaluator:
    def test_evaluate_ranks_strategies(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        evaluator = StrategyEvaluator(engine)

        candidates = [
            StrategyCandidate(
                label="1-stop: Soft→Hard",
                stops=[PitStop(on_lap=5, new_compound=18)],
            ),
            StrategyCandidate(
                label="No stop",
                stops=[],
            ),
        ]

        result = evaluator.evaluate(_make_snapshot(), player_car_index=0, candidates=candidates)

        assert result.player_car_index == 0
        assert len(result.strategies) == 2
        assert result.strategies[0].rank == 1
        assert result.strategies[1].rank == 2
        # Best strategy should have lower or equal mean position
        assert result.strategies[0].mean_position <= result.strategies[1].mean_position

    def test_expected_points_calculation(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        evaluator = StrategyEvaluator(engine)

        candidates = [
            StrategyCandidate(label="Test", stops=[]),
        ]

        result = evaluator.evaluate(_make_snapshot(), player_car_index=0, candidates=candidates)
        strategy = result.strategies[0]
        assert strategy.expected_points >= 0


class TestCheckFeasibility:
    def test_feasible_strategy(self):
        sets = [
            TyreSet(0, 18, 0.0, 30),
            TyreSet(1, 17, 0.0, 25),
        ]
        candidate = StrategyCandidate(
            label="1-stop", stops=[PitStop(on_lap=10, new_compound=18)]
        )
        assert check_feasibility(candidate, sets) is None

    def test_infeasible_not_enough_sets(self):
        sets = [TyreSet(0, 17, 0.0, 25)]
        candidate = StrategyCandidate(
            label="2-stop",
            stops=[
                PitStop(on_lap=10, new_compound=16),
                PitStop(on_lap=20, new_compound=16),
            ],
        )
        reason = check_feasibility(candidate, sets)
        assert reason is not None
        assert "Soft" in reason
        assert "2" in reason

    def test_empty_strategy_always_feasible(self):
        assert check_feasibility(StrategyCandidate(label="No stop", stops=[]), []) is None


class TestTyreSetInfoInSnapshot:
    def test_car_snapshot_with_tyre_sets(self):
        car = CarSnapshot(
            car_index=0,
            driver_name="Player",
            ai_controlled=False,
            position=1,
            tyre_compound=16,
            tyre_age_laps=5,
            fuel_kg=80.0,
            fuel_burn_per_sector_kg=0.18,
            front_wing_damage=0,
            floor_damage=0,
            engine_damage=0,
            num_pit_stops=0,
            total_time_ms=0.0,
            tyre_sets=[
                TyreSetInfo(
                    visual_tyre_compound=16,
                    available=True,
                    wear=10,
                    life_span=20,
                    usable_life=25,
                    lap_delta_time=0,
                    fitted=False,
                ),
                TyreSetInfo(
                    visual_tyre_compound=18,
                    available=True,
                    wear=0,
                    life_span=30,
                    usable_life=35,
                    lap_delta_time=200,
                    fitted=False,
                ),
            ],
        )
        assert len(car.tyre_sets) == 2
        assert car.tyre_sets[0].visual_tyre_compound == 16

    def test_car_snapshot_without_tyre_sets_defaults_empty(self):
        car = _make_car(0)
        assert car.tyre_sets == []

    def test_tyre_set_info_json_aliases(self):
        ts = TyreSetInfo(
            visual_tyre_compound=17,
            available=True,
            wear=5,
            life_span=22,
            usable_life=28,
            lap_delta_time=-150,
            fitted=False,
        )
        data = ts.model_dump(by_alias=True)
        assert data["visualTyreCompound"] == 17
        assert data["lapDeltaTime"] == -150
        assert data["lifeSpan"] == 22
        assert data["usableLife"] == 28
