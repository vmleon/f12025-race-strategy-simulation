"""CLI entry point: python -m calibration run <trackId> | python -m calibration service"""

from __future__ import annotations

import logging
import signal
import sys
import threading

from calibration import db
from calibration.pipeline import run
from calibration.worker import run_worker


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python -m calibration run <trackId>", file=sys.stderr)
        print("       python -m calibration service", file=sys.stderr)
        sys.exit(1)

    command = sys.argv[1]

    if command == "run":
        if len(sys.argv) < 3:
            print("Usage: python -m calibration run <trackId>", file=sys.stderr)
            sys.exit(1)

        track_id = int(sys.argv[2])
        pool = db.get_pool()

        try:
            with pool.acquire() as conn:
                conn.autocommit = False
                run(conn, track_id)
                conn.commit()
        finally:
            db.close_pool()

        print(f"Calibration complete for track {track_id}")

    elif command == "service":
        from calibration.log_config import configure_logging

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

    else:
        print(f"Unknown command: {command}", file=sys.stderr)
        print("Usage: python -m calibration run <trackId>", file=sys.stderr)
        print("       python -m calibration service", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
