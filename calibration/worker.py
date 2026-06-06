"""Continuous calibration worker — dequeues from CALIBRATION_REQUEST and runs pipeline."""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path

import oracledb

from calibration.log_config import session_uid
from calibration.pipeline import run
from calibration.queue import dequeue_calibration_request

logger = logging.getLogger("calibration")


def _apply_session_context(payload: dict) -> object:
    """Set the session_uid contextvar from a message payload.

    Returns a token usable with session_uid.reset() to restore the prior value.
    """
    uid = str(payload.get("sessionUid") or "-")
    return session_uid.set(uid)


def run_calibration(conn: oracledb.Connection, track_id: object) -> None:
    """Run the pipeline for one request in a single transaction.

    Commits once on success; rolls back on any failure so a half-finished fit is
    discarded cleanly instead of being left partially committed.
    """
    conn.autocommit = False
    try:
        run(conn, track_id)
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def run_worker(pool: oracledb.ConnectionPool, shutdown_event: threading.Event):
    logger.info("Calibration worker started")
    heartbeat_path = Path("/tmp/heartbeat")
    heartbeat_path.write_text(str(int(time.time())))
    last_heartbeat = time.monotonic()
    while not shutdown_event.is_set():
        now = time.monotonic()
        if now - last_heartbeat >= 30:
            heartbeat_path.write_text(str(int(time.time())))
            last_heartbeat = now
        try:
            with pool.acquire() as conn:
                request = dequeue_calibration_request(conn, wait=5)
                if request is None:
                    continue

                token = _apply_session_context(request)
                try:
                    track_id = request.get("trackId")
                    trigger = request.get("trigger", "unknown")
                    logger.info("Processing calibration request for track %s (trigger: %s)", track_id, trigger)

                    run_calibration(conn, track_id)
                    logger.info("Calibration complete for track %s", track_id)
                finally:
                    session_uid.reset(token)

        except Exception:
            logger.error("Error processing calibration request", exc_info=True)

    logger.info("Calibration worker stopped")
