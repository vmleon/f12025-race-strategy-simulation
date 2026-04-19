import logging
import re
from pathlib import Path

import pytest

from simulator.log_config import configure_logging, session_uid


LINE_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} "
    r"\[(INFO |WARN |ERROR|DEBUG|TRACE)\] "
    r"\[simulator   \] "
    r"\[sess=(\S{1,10} *)\] "
    r".+$"
)


@pytest.fixture
def tmp_log(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    (tmp_path / "logs").mkdir()
    yield tmp_path / "logs" / "simulator.log"
    for h in list(logging.getLogger().handlers):
        logging.getLogger().removeHandler(h)


def test_configure_logging_writes_formatted_line_to_file(tmp_log):
    configure_logging()
    logging.getLogger("simulator").info("hello")

    contents = tmp_log.read_text().strip().splitlines()
    assert len(contents) == 1
    assert LINE_PATTERN.match(contents[0]), f"line did not match: {contents[0]}"


def test_session_uid_contextvar_appears_in_line(tmp_log):
    configure_logging()
    session_uid.set("42")
    logging.getLogger("simulator").info("event")

    line = tmp_log.read_text().strip()
    assert "[sess=42        ]" in line


def test_default_sessionuid_is_dash(tmp_log):
    configure_logging()
    logging.getLogger("simulator").info("startup")

    line = tmp_log.read_text().strip()
    assert "[sess=-         ]" in line
