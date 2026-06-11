from simulator.coefficients import Coefficients
from simulator.engine import MonteCarloEngine
from simulator.models import (
    CarSnapshot,
    PitStop,
    RaceSnapshot,
    RankedStrategy,
    StrategyCandidate,
    TyreSetInfo,
)
from simulator.strategy import (
    RANK_NOISE_TOL,
    StrategyEvaluator,
    TyreSet,
    _insufficient_calibration,
    _rank_key,
    check_feasibility,
    expected_points,
)


def _ranked(label: str, mean_pos: float, stops: list[PitStop]) -> RankedStrategy:
    return RankedStrategy(
        rank=0,
        candidate=StrategyCandidate(label=label, stops=stops),
        mean_position=mean_pos,
        position_std_dev=0.0,
        ci95_low=0.0,
        ci95_high=0.0,
        dnf_probability=0.0,
        top3_probability=0.0,
        points_finish_probability=0.0,
        expected_points=0.0,
    )


class TestRankKey:
    """A: ranking is stable and conservative when candidates are within noise."""

    def test_clearly_better_candidate_wins(self):
        # 0.7 positions apart (> RANK_NOISE_TOL) — the better mean wins regardless of stops.
        two = _ranked("2-stop", 2.0, [PitStop(on_lap=5, new_compound=17),
                                      PitStop(on_lap=10, new_compound=18)])
        nostop = _ranked("No stop", 2.7, [])
        assert sorted([nostop, two], key=_rank_key)[0].candidate.label == "2-stop"

    def test_tie_prefers_fewer_stops(self):
        # All within noise -> the conservative (fewest stops) plan wins even if its
        # mean is marginally worse than a 2-stop's.
        nostop = _ranked("No stop", 3.1, [])
        one = _ranked("1-stop L8", 3.0, [PitStop(on_lap=8, new_compound=18)])
        two = _ranked("2-stop", 2.95, [PitStop(on_lap=6, new_compound=17),
                                       PitStop(on_lap=11, new_compound=18)])
        assert sorted([two, one, nostop], key=_rank_key)[0].candidate.label == "No stop"

    def test_tie_prefers_later_first_stop(self):
        early = _ranked("1-stop L4", 3.0, [PitStop(on_lap=4, new_compound=18)])
        late = _ranked("1-stop L10", 3.1, [PitStop(on_lap=10, new_compound=18)])
        assert sorted([early, late], key=_rank_key)[0].candidate.label == "1-stop L10"

    def test_stable_under_subnoise_perturbation(self):
        # Same candidates, tiny mean perturbations (< tol) -> identical rank-1.
        a = [_ranked("No stop", 3.05, []),
             _ranked("1-stop L9", 3.00, [PitStop(on_lap=9, new_compound=18)])]
        b = [_ranked("No stop", 2.98, []),
             _ranked("1-stop L9", 3.07, [PitStop(on_lap=9, new_compound=18)])]
        assert sorted(a, key=_rank_key)[0].candidate.label == "No stop"
        assert sorted(b, key=_rank_key)[0].candidate.label == "No stop"


class TestInsufficientCalibration:
    """B: honest insufficient-calibration flag."""

    def test_no_baseline_for_current_compound_is_insufficient(self):
        car = _make_car(0, ai_controlled=False)  # default sector_baseline_ms = [0,0,0]
        assert _insufficient_calibration(car, "observed") is True

    def test_circuit_default_pace_is_insufficient(self):
        car = _make_car(0, ai_controlled=False)
        car.sector_baseline_ms = [23000, 32000, 24000]
        assert _insufficient_calibration(car, "circuit_default") is True

    def test_fitted_baseline_with_observed_pace_is_sufficient(self):
        car = _make_car(0, ai_controlled=False)
        car.sector_baseline_ms = [23000, 32000, 24000]
        assert _insufficient_calibration(car, "observed") is False

    def test_missing_player_is_not_flagged(self):
        assert _insufficient_calibration(None, "observed") is False


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


class TestInsufficientCalibration:
    def test_flag_true_when_player_pace_is_circuit_default(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        evaluator = StrategyEvaluator(engine)
        # Default cars carry no observed laps and no fitted baseline → the player
        # pace falls back to the generic circuit default.
        candidates = [
            StrategyCandidate(label="No stop", stops=[]),
            StrategyCandidate(label="1-stop", stops=[PitStop(on_lap=5, new_compound=18)]),
        ]
        result = evaluator.evaluate(_make_snapshot(), player_car_index=0, candidates=candidates)
        assert result.insufficient_calibration is True

    def test_flag_false_when_player_has_baseline(self):
        engine = MonteCarloEngine(Coefficients.defaults(), seed=42)
        evaluator = StrategyEvaluator(engine)
        snapshot = _make_snapshot()
        snapshot.cars[0].sector_baseline_ms = [30000, 30000, 30000]
        candidates = [StrategyCandidate(label="No stop", stops=[])]
        result = evaluator.evaluate(snapshot, player_car_index=0, candidates=candidates)
        assert result.insufficient_calibration is False


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
