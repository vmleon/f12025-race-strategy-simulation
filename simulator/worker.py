from __future__ import annotations

import logging
import threading

import oracledb

from simulator.coefficients import Coefficients
from simulator.db import load_coefficients_for_track
from simulator.engine import MonteCarloEngine
from simulator.models import RaceSnapshot
from simulator.queue import dequeue_simulation_request, enqueue_simulation_result

logger = logging.getLogger("simulator")


def run_worker(pool: oracledb.ConnectionPool, shutdown_event: threading.Event):
    logger.info("Queue worker started")
    while not shutdown_event.is_set():
        try:
            with pool.acquire() as conn:
                request = dequeue_simulation_request(conn, wait=5)
                if request is None:
                    continue

                job_id = request.get("jobId")
                snapshot_data = request.get("raceSnapshot")
                logger.info("Queue worker: processing job %s", job_id)

                snapshot = RaceSnapshot.model_validate(snapshot_data)

                try:
                    coefficients = load_coefficients_for_track(snapshot.track_id)
                except Exception:
                    logger.warning("DB coefficients unavailable, using defaults", exc_info=True)
                    coefficients = Coefficients.defaults()

                engine = MonteCarloEngine(coefficients)
                result = engine.simulate(snapshot)

                result_payload = {
                    "jobId": job_id,
                    "result": result.model_dump(by_alias=True),
                }
                enqueue_simulation_result(conn, result_payload)
                logger.info("Queue worker: job %s completed (%dms, %d iterations)",
                            job_id, result.wall_clock_ms, result.iterations)

        except Exception:
            logger.error("Queue worker: error processing message", exc_info=True)

    logger.info("Queue worker stopped")
