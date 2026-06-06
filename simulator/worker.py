from __future__ import annotations

import logging
import threading

import oracledb

from simulator.candidate_generator import generate_candidates
from simulator.coefficients import Coefficients
from simulator.db import load_coefficients_for_track
from simulator.engine import MonteCarloEngine
from simulator.log_config import session_uid
from simulator.models import RaceSnapshot, StrategyEvaluation
from simulator.queue import dequeue_simulation_request, enqueue_simulation_result, dequeue_strategy_request, enqueue_strategy_result
from simulator.strategy import StrategyEvaluator

logger = logging.getLogger("simulator")


def _apply_session_context(payload: dict) -> object:
    """Set the session_uid contextvar from a message payload.

    Returns a token usable with session_uid.reset() to restore the prior value.
    """
    uid = str(payload.get("sessionUid") or "-")
    return session_uid.set(uid)


def run_worker(pool: oracledb.ConnectionPool, shutdown_event: threading.Event):
    logger.info("Queue worker started")
    while not shutdown_event.is_set():
        try:
            with pool.acquire() as conn:
                request = dequeue_simulation_request(conn, wait=5)
                if request is None:
                    continue

                token = _apply_session_context(request)
                try:
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
                finally:
                    session_uid.reset(token)

        except Exception:
            logger.error("Queue worker: error processing message", exc_info=True)

    logger.info("Queue worker stopped")


def run_strategy_worker(pool: oracledb.ConnectionPool, shutdown_event: threading.Event):
    logger.info("Strategy queue worker started")
    while not shutdown_event.is_set():
        try:
            with pool.acquire() as conn:
                request_data = dequeue_strategy_request(conn, wait=5)
                if request_data is None:
                    continue

                token = _apply_session_context(request_data)
                try:
                    job_id = request_data.get("jobId")
                    snapshot_data = request_data.get("raceSnapshot")
                    player_car_index = request_data.get("playerCarIndex", 0)
                    logger.info("Strategy worker: processing job %s", job_id)

                    snapshot = RaceSnapshot.model_validate(snapshot_data)

                    try:
                        coefficients = load_coefficients_for_track(snapshot.track_id)
                    except Exception:
                        logger.warning("DB coefficients unavailable, using defaults", exc_info=True)
                        coefficients = Coefficients.defaults()

                    candidates = generate_candidates(snapshot, player_car_index, coefficients)
                    if not candidates:
                        result = StrategyEvaluation(
                            player_car_index=player_car_index, strategies=[]
                        )
                    else:
                        engine = MonteCarloEngine(coefficients)
                        evaluator = StrategyEvaluator(engine)
                        result = evaluator.evaluate(snapshot, player_car_index, candidates)

                    result_payload = {
                        "jobId": job_id,
                        "evaluatedAtLap": snapshot.current_lap,
                        "result": result.model_dump(by_alias=True),
                    }
                    enqueue_strategy_result(conn, result_payload)
                    logger.info("Strategy worker: job %s completed (%d candidates)",
                                job_id, len(candidates))
                finally:
                    session_uid.reset(token)

        except Exception:
            logger.error("Strategy worker: error processing message", exc_info=True)

    logger.info("Strategy queue worker stopped")
