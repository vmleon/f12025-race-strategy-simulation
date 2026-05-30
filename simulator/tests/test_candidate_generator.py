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

    def test_empty_tyre_sets_uses_default_compounds(self):
        snapshot = _make_snapshot(current_lap=5, total_laps=50, player_tyre_sets=[])
        candidates = generate_candidates(snapshot, player_car_index=0)
        assert len(candidates) > 0, "Should generate candidates with default S/M/H compounds"
        compounds_used = {s.new_compound for c in candidates for s in c.stops}
        # Should use at least one non-current compound (player is on Soft=16)
        assert len(compounds_used) > 0

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

    def test_filters_candidates_with_overlong_soft_stint(self):
        """A 1-stop candidate where the Soft stint exceeds the lifespan (30) must be pruned."""
        # Player on Soft (16) at lap 5, total 50 laps, tyre_age=5 (default _make_car).
        # Effective first-stint length = (pit_lap - 5) + 5.
        # Lifespan(Soft) = 30, so pit_lap must be <= 30 to keep the candidate.
        # player_pits=1 satisfies the two-compound rule so candidates aren't filtered for that reason.
        tyre_sets = [
            _make_tyre_set(16, fitted=True, usable_life=40, wear=5),
            _make_tyre_set(17, available=True),
        ]
        snapshot = _make_snapshot(current_lap=5, total_laps=50, player_compound=16,
                                  player_pits=1, player_tyre_sets=tyre_sets)

        candidates = generate_candidates(snapshot, player_car_index=0)

        # Every retained 1-stop candidate's effective Soft first stint must be <= 30 laps.
        for c in candidates:
            if len(c.stops) == 1:
                effective_stint = (c.stops[0].on_lap - 5) + 5  # +5 for starting tyre_age
                assert effective_stint <= 30, (
                    f"Candidate {c.label}: soft first stint = {effective_stint} laps, exceeds lifespan 30"
                )

    def test_keeps_valid_short_stint_candidates(self):
        """A scenario where 1-stop candidates fit within lifespan must produce some candidates."""
        tyre_sets = [
            _make_tyre_set(17, fitted=True, usable_life=37, wear=0),
            _make_tyre_set(16, available=True),
        ]
        snapshot = _make_snapshot(current_lap=5, total_laps=20, player_compound=17,
                                  player_pits=1, player_tyre_sets=tyre_sets)

        candidates = generate_candidates(snapshot, player_car_index=0)

        one_stop = [c for c in candidates if len(c.stops) == 1]
        assert len(one_stop) > 0, (
            f"Expected at least one valid 1-stop candidate, got: {[c.label for c in candidates]}"
        )


class TestRepairCandidates:
    def _snapshot(self, front_wing_damage):
        tyre_sets = [
            _make_tyre_set(16, fitted=True),
            _make_tyre_set(17, available=True),
            _make_tyre_set(18, available=True),
        ]
        player = CarSnapshot(
            car_index=0, driver_name="Player", ai_controlled=False,
            position=1, tyre_compound=16, tyre_age_laps=5,
            fuel_kg=80.0, fuel_burn_per_sector_kg=0.18,
            front_wing_damage=front_wing_damage, floor_damage=0, engine_damage=0,
            num_pit_stops=1,  # two-compound rule already met -> stops generated freely
            total_time_ms=0.0, tyre_sets=tyre_sets,
        )
        return RaceSnapshot(
            track_id=0, total_laps=50, current_lap=10, current_sector=0,
            weather=0, track_temp=35, air_temp=25, safety_car=False,
            cars=[player, _make_car(1), _make_car(2)],
        )

    def test_damaged_wing_marks_first_stop_repair(self):
        candidates = generate_candidates(self._snapshot(60), player_car_index=0)
        with_stops = [c for c in candidates if c.stops]
        assert with_stops, "expected at least one stopping candidate"
        for c in with_stops:
            assert c.stops[0].repair_front_wing is True
            assert "+FW" in c.label

    def test_healthy_wing_no_repair(self):
        candidates = generate_candidates(self._snapshot(0), player_car_index=0)
        for c in candidates:
            for stop in c.stops:
                assert stop.repair_front_wing is False
            assert "+FW" not in c.label
