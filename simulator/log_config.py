"""Centralized logging configuration for the simulator service.

Exposes:
- session_uid: ContextVar populated per work-unit (default "-")
- configure_logging(): installs console + file handlers with the shared format
"""

from __future__ import annotations

import logging
from contextvars import ContextVar
from pathlib import Path

session_uid: ContextVar[str] = ContextVar("session_uid", default="-")

LOG_PATH = Path("logs") / "simulator.log"
FORMAT = (
    "%(asctime)s.%(msecs)03d "
    "[%(levelname)-5s] "
    "[simulator   ] "
    "[sess=%(session_uid)-10.10s] "
    "%(message)s"
)
DATEFMT = "%Y-%m-%d %H:%M:%S"


class _SessionUidFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.session_uid = session_uid.get()
        return True


def configure_logging(level: int = logging.INFO) -> None:
    session_uid.set("-")
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)

    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)

    formatter = logging.Formatter(FORMAT, datefmt=DATEFMT)
    uid_filter = _SessionUidFilter()

    console = logging.StreamHandler()
    console.setFormatter(formatter)
    console.addFilter(uid_filter)

    file_handler = logging.FileHandler(LOG_PATH, mode="w", encoding="utf-8")
    file_handler.setFormatter(formatter)
    file_handler.addFilter(uid_filter)

    root.setLevel(level)
    root.addHandler(console)
    root.addHandler(file_handler)
