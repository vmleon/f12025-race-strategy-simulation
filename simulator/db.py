from __future__ import annotations

import os

import oracledb

from simulator.coefficients import Coefficients
from simulator.strategy import TyreSet

_pool: oracledb.ConnectionPool | None = None


def get_pool() -> oracledb.ConnectionPool:
    """Returns a shared connection pool, creating it on first call."""
    global _pool
    if _pool is None:
        _pool = oracledb.create_pool(
            user=os.environ.get("ORACLE_USER", "F1SIM"),
            password=os.environ.get("ORACLE_PASSWORD", "F1SIM"),
            dsn=os.environ.get("ORACLE_DSN", "localhost:1521/FREEPDB1"),
            min=1,
            max=4,
        )
    return _pool


def close_pool() -> None:
    """Closes the connection pool if it exists."""
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None


def load_coefficients_for_track(track_id: int) -> Coefficients:
    """Loads the most recent coefficient for each (knob, regime, sector) combination
    for the given track. Returns a populated Coefficients instance."""
    coefficients = Coefficients.defaults()
    pool = get_pool()

    with pool.acquire() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT knob_name, calibration_regime, sector_number, value
                FROM (
                    SELECT knob_name, calibration_regime, sector_number, value,
                           ROW_NUMBER() OVER (
                               PARTITION BY knob_name, calibration_regime, NVL(sector_number, -1)
                               ORDER BY trained_at DESC
                           ) rn
                    FROM calibration_coefficients
                    WHERE track_id = :1
                )
                WHERE rn = 1
                """,
                [track_id],
            )
            for row in cursor:
                knob_name, regime, sector_number, value = row
                sector = sector_number if sector_number is not None else -1
                coefficients.put(knob_name, regime, sector, value)

    return coefficients


def load_available_tyre_sets(session_uid: int, car_index: int) -> list[TyreSet]:
    """Returns available (not fitted, not fully worn) tyre sets for a car in a session."""
    pool = get_pool()

    with pool.acquire() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT set_index, tyre_compound_actual, wear, usable_life
                FROM tyre_sets
                WHERE session_uid = :1 AND car_index = :2
                  AND available = 1 AND fitted = 0
                ORDER BY set_index
                """,
                [session_uid, car_index],
            )
            return [
                TyreSet(
                    set_index=row[0],
                    compound=row[1],
                    wear=row[2],
                    usable_life=row[3],
                )
                for row in cursor
            ]
