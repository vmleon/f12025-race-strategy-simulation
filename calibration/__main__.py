"""Calibration service entry point: `python -m calibration` starts the worker."""

from __future__ import annotations

import logging
import signal
import sys
import threading

from calibration import db
from calibration.log_config import configure_logging
from calibration.worker import run_worker


def main() -> None:
    if len(sys.argv) > 1:
        print("Usage: python -m calibration", file=sys.stderr)
        print("(no arguments — starts the worker that consumes CALIBRATION_REQUEST)", file=sys.stderr)
        sys.exit(2)

    configure_logging()
    logger = logging.getLogger("calibration")
    logger.info("Starting calibration service")

    pool = db.get_pool()
    shutdown_event = threading.Event()

    def handle_signal(signum, frame):
        logger.info("Shutdown signal received")
        shutdown_event.set()

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    try:
        run_worker(pool, shutdown_event)
    finally:
        db.close_pool()
        logger.info("Calibration service stopped")


if __name__ == "__main__":
    main()
