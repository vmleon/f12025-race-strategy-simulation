from simulator.candidate_generator import generate_candidates
from simulator.models import CarSnapshot, PitStop, RaceSnapshot, TyreSetInfo


def _make_tyre_set(compound: int, available: bool = True, lap_delta: int = 0,
                   usable_life: int = 30, fitted: bool = False, wear: int = 0) -> TyreSetInfo:
    return TyreSetInfo(
        visual_tyre_compound=compound,
        available=available,
        wear=wear,
        life_span=usable_life - wear,
        usable_life=usable_life,
        lap_delta_time=lap_delta,
        fitted=fitted,
    )


def _make_car(car_index: int, tyre_compound: int = 16, num_pit_stops: int = 0,
              tyre_sets: list[TyreSetInfo] | None = None) -> CarSnapshot:
    return CarSnapshot(
        car_index=car_index,
        driver_name="Driver",
        ai_controlled=car_index != 0,
        position=car_index + 1,
        tyre_compound=tyre_compound,
        tyre_age_laps=5,
        fuel_kg=80.0,
        fuel_burn_per_sector_kg=0.18,
        front_wing_damage=0,
        floor_damage=0,
        engine_damage=0,
        num_pit_stops=num_pit_stops,
        total_time_ms=0.0,
        tyre_sets=tyre_sets or [],
    )


def _make_snapshot(current_lap: int = 5, total_laps: int = 50,
                   player_compound: int = 16, player_pits: int = 0,
                   player_tyre_sets: list[TyreSetInfo] | None = None) -> RaceSnapshot:
    return RaceSnapshot(
        track_id=0,
        total_laps=total_laps,
        current_lap=current_lap,
        current_sector=0,
        weather=0,
        track_temp=35,
        air_temp=25,
        safety_car=False,
        cars=[
            _make_car(0, tyre_compound=player_compound, num_pit_stops=player_pits,
                      tyre_sets=player_tyre_sets),
            _make_car(1),
            _make_car(2),
        ],
    )


class TestGenerateCandidates:
    def test_generates_candidates_with_available_tyres(self):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=True),
            _make_tyre_set(18, available=True),
        ]
        snapshot = _make_snapshot(current_lap=10, total_laps=50, player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        assert len(candidates) > 0
        assert all(c.label for c in candidates)

    def test_no_unavailable_compounds_used(self):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=False),
            _make_tyre_set(18, available=True),
        ]
        snapshot = _make_snapshot(current_lap=10, total_laps=50, player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        for c in candidates:
            for stop in c.stops:
                assert stop.new_compound != 17, "Should not use unavailable Medium compound"

    def test_enforces_two_compound_rule(self):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=True),
            _make_tyre_set(18, available=True),
        ]
        snapshot = _make_snapshot(current_lap=5, total_laps=50,
                                 player_compound=16, player_pits=0,
                                 player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        zero_stop = [c for c in candidates if len(c.stops) == 0]
        assert len(zero_stop) == 0

    def test_zero_stop_allowed_after_compound_change(self):
        tyre_sets = [
            _make_tyre_set(18, fitted=True, usable_life=50),
        ]
        snapshot = _make_snapshot(current_lap=30, total_laps=50,
                                 player_compound=18, player_pits=1,
                                 player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        zero_stop = [c for c in candidates if len(c.stops) == 0]
        assert len(zero_stop) == 1

    def test_prunes_slow_compounds(self):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=True, lap_delta=500),
            _make_tyre_set(18, available=True, lap_delta=10000),
        ]
        snapshot = _make_snapshot(current_lap=10, total_laps=50, player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        hard_stops = [s for c in candidates for s in c.stops if s.new_compound == 18]
        assert len(hard_stops) == 0, "Should not generate strategies with very slow compound"

    def test_no_two_stop_when_few_laps_remain(self):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=True),
            _make_tyre_set(18, available=True),
        ]
        snapshot = _make_snapshot(current_lap=42, total_laps=50, player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        two_stop = [c for c in candidates if len(c.stops) == 2]
        assert len(two_stop) == 0

    def test_candidate_count_bounded(self):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=True),
            _make_tyre_set(18, available=True),
        ]
        snapshot = _make_snapshot(current_lap=1, total_laps=70, player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        assert len(candidates) <= 50

    def test_empty_tyre_sets_returns_empty(self):
        snapshot = _make_snapshot(current_lap=10, total_laps=50, player_tyre_sets=[])
        candidates = generate_candidates(snapshot, player_car_index=0)
        assert len(candidates) == 0

    def test_pit_laps_in_valid_range(self):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=True),
            _make_tyre_set(18, available=True),
        ]
        snapshot = _make_snapshot(current_lap=10, total_laps=50, player_tyre_sets=tyre_sets)
        candidates = generate_candidates(snapshot, player_car_index=0)
        for c in candidates:
            for stop in c.stops:
                assert stop.on_lap > 10, f"Pit lap {stop.on_lap} should be > current lap 10"
                assert stop.on_lap < 50, f"Pit lap {stop.on_lap} should be < total laps 50"
