from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from simulator.coefficients import Coefficients
from simulator.db import close_pool, load_coefficients_for_track
from simulator.engine import MonteCarloEngine
from simulator.models import (
    RaceSnapshot,
    SimulationResult,
    StrategyEvaluation,
    StrategyEvaluationRequest,
)
from simulator.strategy import StrategyEvaluator

logger = logging.getLogger("simulator")


@asynccontextmanager
async def lifespan(application: FastAPI):
    yield
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


