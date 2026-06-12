import datetime

import manage


def test_build_circuit_names_maps_known_tracks():
    names = manage._build_circuit_names()
    assert names.get(0) == "Melbourne"
    assert names.get(5) == "Monaco"
    assert names.get(10) == "Spa-Francorchamps"


def _sample_row():
    return {
        "message_id": 1, "session_uid": 123,
        "sent_at": datetime.datetime(2026, 6, 12, 14, 30, 5),
        "priority": "HIGH", "session_type": 10, "track_id": 5,
        "lap_number": 12, "total_laps": 44, "player_position": 8,
        "tyre_compound": "soft", "tyre_age_laps": 6, "sector": 2,
        "message_text": "Box, box.", "best_strategies": '{"a":1}',
        "rendered_text": "Box, box.",
    }


def test_radio_csv_row_resolves_and_formats():
    out = manage._radio_csv_row(_sample_row(), {5: "Monaco"}, {123: "SAINZ"})
    assert out == [
        "1", "123", "2026-06-12 14:30:05",
        "HIGH", "10", "5", "Monaco",
        "12", "44", "8", "SAINZ",
        "soft", "6", "2",
        "Box, box.", '{"a":1}', "Box, box.",
    ]


def test_radio_csv_row_header_length_matches():
    out = manage._radio_csv_row(_sample_row(), {5: "Monaco"}, {123: "SAINZ"})
    assert len(out) == len(manage._RADIO_CSV_HEADER)


def test_radio_csv_row_track_fallback_and_nulls():
    row = {
        "message_id": 2, "session_uid": 999, "sent_at": None,
        "priority": "NORMAL", "session_type": 10, "track_id": 99,
        "lap_number": None, "total_laps": None, "player_position": None,
        "tyre_compound": None, "tyre_age_laps": None, "sector": None,
        "message_text": "Hi", "best_strategies": None, "rendered_text": "Hi",
    }
    out = manage._radio_csv_row(row, {}, {})
    assert out[6] == "Track 99"   # circuit_name fallback (column index 6)
    assert out[10] == ""          # driver_name missing (column index 10)
    assert out[2] == ""           # sent_at None
    assert out[15] == ""          # best_strategies None (column index 15)
