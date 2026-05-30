from simulator.models import PitStop


def test_pit_stop_repair_defaults_false():
    stop = PitStop(on_lap=10, new_compound=17)
    assert stop.repair_front_wing is False


def test_pit_stop_repair_can_be_set():
    stop = PitStop(on_lap=10, new_compound=17, repair_front_wing=True)
    assert stop.repair_front_wing is True


def test_pit_stop_repair_alias():
    stop = PitStop.model_validate({"onLap": 10, "newCompound": 17, "repairFrontWing": True})
    assert stop.repair_front_wing is True
