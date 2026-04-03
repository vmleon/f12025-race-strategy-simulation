"""Continuous calibration worker — dequeues from CALIBRATION_REQUEST and runs pipeline."""

from __future__ import annotations

import logging
import threading

import oracledb

from calibration.pipeline import run
from calibration.queue import dequeue_calibration_request

logger = logging.getLogger("calibration")


def run_worker(pool: oracledb.ConnectionPool, shutdown_event: threading.Event):
    logger.info("Calibration worker started")
    while not shutdown_event.is_set():
        try:
            with pool.acquire() as conn:
                request = dequeue_calibration_request(conn, wait=5)
                if request is None:
                    continue

                track_id = request.get("trackId")
                trigger = request.get("trigger", "unknown")
                logger.info("Processing calibration request for track %s (trigger: %s)", track_id, trigger)

                conn.autocommit = False
                run(conn, track_id)
                conn.commit()
                logger.info("Calibration complete for track %s", track_id)

        except Exception:
            logger.error("Error processing calibration request", exc_info=True)

    logger.info("Calibration worker stopped")
