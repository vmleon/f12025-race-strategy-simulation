import logging
import re

import pytest

from calibration.log_config import configure_logging, session_uid


LINE_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} "
    r"\[(INFO |WARN |ERROR|DEBUG|TRACE)\] "
    r"\[calibration \] "
    r"\[sess=(\S{1,10} *)\] "
    r".+$"
)


@pytest.fixture(autouse=True)
def reset_handlers():
    yield
    for h in list(logging.getLogger().handlers):
        logging.getLogger().removeHandler(h)


def test_configure_logging_writes_formatted_line_to_stderr(capsys):
    configure_logging()
    logging.getLogger("calibration").info("hello")

    err = capsys.readouterr().err.strip().splitlines()
    assert len(err) == 1
    assert LINE_PATTERN.match(err[0]), f"line did not match: {err[0]}"


def test_session_uid_contextvar_appears_in_line(capsys):
    configure_logging()
    session_uid.set("42")
    logging.getLogger("calibration").info("event")

    line = capsys.readouterr().err.strip()
    assert "[sess=42        ]" in line


def test_default_sessionuid_is_dash(capsys):
    configure_logging()
    logging.getLogger("calibration").info("startup")

    line = capsys.readouterr().err.strip()
    assert "[sess=-         ]" in line
