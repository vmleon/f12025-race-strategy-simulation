"""Calibration entry point.

  python -m calibration              start the worker (consumes CALIBRATION_REQUEST)
  python -m calibration run <trackId>  run the pipeline once for one track, then exit
"""

from __future__ import annotations

import logging
import signal
import sys
import threading

from calibration import db
from calibration.log_config import configure_logging
from calibration.worker import run_calibration, run_worker

_USAGE = (
    "Usage:\n"
    "  python -m calibration              start the worker (consumes CALIBRATION_REQUEST)\n"
    "  python -m calibration run <trackId>  run the pipeline once for one track, then exit"
)


def _parse_args(argv: list[str]) -> tuple[str, int | None]:
    """Returns ('worker', None) or ('run', track_id). Exits(2) on invalid usage."""
    if not argv:
        return ("worker", None)
    if argv[0] == "run" and len(argv) == 2:
        try:
            return ("run", int(argv[1]))
        except ValueError:
            pass
    print(_USAGE, file=sys.stderr)
    sys.exit(2)


def main() -> None:
    mode, track_id = _parse_args(sys.argv[1:])
    configure_logging()
    logger = logging.getLogger("calibration")
    pool = db.get_pool()

    if mode == "run":
        logger.info("Running one-off calibration for track %s", track_id)
        try:
            with pool.acquire() as conn:
                run_calibration(conn, track_id)
            logger.info("Calibration complete for track %s", track_id)
        finally:
            db.close_pool()
        return

    logger.info("Starting calibration service")
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
