from calibration.log_config import session_uid
from calibration.worker import _apply_session_context


def test_apply_session_context_sets_contextvar():
    payload = {"trackId": 7, "sessionUid": "12345"}
    token = _apply_session_context(payload)
    try:
        assert session_uid.get() == "12345"
    finally:
        session_uid.reset(token)
    assert session_uid.get() == "-"


def test_apply_session_context_defaults_to_dash_when_missing():
    payload = {"trackId": 7}
    token = _apply_session_context(payload)
    try:
        assert session_uid.get() == "-"
    finally:
        session_uid.reset(token)
