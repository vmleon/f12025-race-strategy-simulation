"""Centralized logging configuration for the calibration service.

Exposes:
- session_uid: ContextVar populated per work-unit (default "-")
- configure_logging(): installs a console handler with the shared format
"""

from __future__ import annotations

import logging
from contextvars import ContextVar

session_uid: ContextVar[str] = ContextVar("session_uid", default="-")

FORMAT = (
    "%(asctime)s.%(msecs)03d "
    "[%(levelname)-5s] "
    "[calibration ] "
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

    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)

    formatter = logging.Formatter(FORMAT, datefmt=DATEFMT)
    uid_filter = _SessionUidFilter()

    console = logging.StreamHandler()
    console.setFormatter(formatter)
    console.addFilter(uid_filter)

    root.setLevel(level)
    root.addHandler(console)
