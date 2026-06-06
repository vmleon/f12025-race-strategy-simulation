import pytest

from calibration import worker
from calibration.log_config import session_uid
from calibration.worker import _apply_session_context, run_calibration


class _FakeConn:
    def __init__(self):
        self.autocommit = True
        self.committed = False
        self.rolled_back = False

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True


def test_run_calibration_commits_on_success(monkeypatch):
    monkeypatch.setattr(worker, "run", lambda conn, track_id: None)
    conn = _FakeConn()
    run_calibration(conn, 4)
    assert conn.autocommit is False
    assert conn.committed is True
    assert conn.rolled_back is False


def test_run_calibration_rolls_back_on_failure(monkeypatch):
    def boom(conn, track_id):
        raise RuntimeError("bad row")

    monkeypatch.setattr(worker, "run", boom)
    conn = _FakeConn()
    with pytest.raises(RuntimeError):
        run_calibration(conn, 4)
    assert conn.rolled_back is True
    assert conn.committed is False


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
