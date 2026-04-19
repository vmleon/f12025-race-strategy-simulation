from simulator.log_config import session_uid
from simulator.worker import _apply_session_context


def test_apply_session_context_sets_contextvar():
    payload = {"jobId": "sim-1", "sessionUid": "987"}
    token = _apply_session_context(payload)
    try:
        assert session_uid.get() == "987"
    finally:
        session_uid.reset(token)
    assert session_uid.get() == "-"


def test_apply_session_context_defaults_to_dash_when_missing():
    payload = {"jobId": "sim-1"}
    token = _apply_session_context(payload)
    try:
        assert session_uid.get() == "-"
    finally:
        session_uid.reset(token)
