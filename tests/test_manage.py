import datetime

import manage


def test_build_circuit_names_maps_known_tracks():
    names = manage._build_circuit_names()
    assert names.get(0) == "Melbourne"
    assert names.get(5) == "Monaco"
    assert names.get(10) == "Spa-Francorchamps"
