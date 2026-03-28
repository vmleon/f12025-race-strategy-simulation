from __future__ import annotations

import logging
import os
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from simulator.coefficients import Coefficients
from simulator.db import close_pool, get_pool, load_coefficients_for_track
from simulator.engine import MonteCarloEngine
from simulator.models import (
    RaceSnapshot,
    SimulationResult,
    StrategyEvaluation,
    StrategyEvaluationRequest,
)
from simulator.strategy import StrategyEvaluator

logger = logging.getLogger("simulator")


_shutdown_event = threading.Event()
_worker_thread: threading.Thread | None = None


@asynccontextmanager
async def lifespan(application: FastAPI):
    global _worker_thread
    if _use_db:
        from simulator.worker import run_worker

        _worker_thread = threading.Thread(
            target=run_worker,
            args=(get_pool(), _shutdown_event),
            name="queue-worker",
            daemon=True,
        )
        _worker_thread.start()
    yield
    _shutdown_event.set()
    if _worker_thread is not None:
        _worker_thread.join(timeout=10)
    close_pool()


app = FastAPI(title="F1 Monte Carlo Simulator", lifespan=lifespan)

_use_db: bool = os.environ.get("SIMULATOR_USE_DB", "true").lower() == "true"


def _load_coefficients(track_id: int) -> Coefficients:
    if _use_db:
        try:
            return load_coefficients_for_track(track_id)
        except Exception:
            logger.warning("DB unavailable, falling back to defaults", exc_info=True)
    return Coefficients.defaults()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/simulate", response_model_by_alias=True)
def simulate(snapshot: RaceSnapshot) -> SimulationResult:
    coefficients = _load_coefficients(snapshot.track_id)
    engine = MonteCarloEngine(coefficients)
    return engine.simulate(snapshot)


@app.post("/evaluate-strategies", response_model_by_alias=True)
def evaluate_strategies(request: StrategyEvaluationRequest) -> StrategyEvaluation:
    if not request.candidates:
        raise HTTPException(status_code=400, detail="No candidates provided")
    coefficients = _load_coefficients(request.snapshot.track_id)
    engine = MonteCarloEngine(coefficients)
    evaluator = StrategyEvaluator(engine)
    return evaluator.evaluate(
        request.snapshot, request.player_car_index, request.candidates
    )


