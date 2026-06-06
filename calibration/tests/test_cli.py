import pytest

from calibration.__main__ import _parse_args


def test_no_args_starts_worker():
    assert _parse_args([]) == ("worker", None)


def test_run_with_track_id():
    assert _parse_args(["run", "4"]) == ("run", 4)


def test_run_without_track_id_exits_2():
    with pytest.raises(SystemExit) as exc:
        _parse_args(["run"])
    assert exc.value.code == 2


def test_run_with_non_integer_track_id_exits_2():
    with pytest.raises(SystemExit) as exc:
        _parse_args(["run", "abc"])
    assert exc.value.code == 2


def test_unknown_argument_exits_2():
    with pytest.raises(SystemExit) as exc:
        _parse_args(["bogus"])
    assert exc.value.code == 2
