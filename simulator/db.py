from __future__ import annotations

import configparser
import os

import oracledb

from simulator.coefficients import Coefficients
from simulator.strategy import TyreSet
from simulator.tyre_curve import TyreCurves

_pool: oracledb.ConnectionPool | None = None

_CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config.properties")


def _read_config() -> configparser.ConfigParser:
    config = configparser.ConfigParser()
    config.read(_CONFIG_PATH)
    return config


def get_pool() -> oracledb.ConnectionPool:
    """Returns a shared connection pool, creating it on first call."""
    global _pool
    if _pool is None:
        cfg = _read_config()
        dsn = cfg.get("database", "dsn", fallback="localhost:1521/FREEPDB1")
        host = os.environ.get("ORACLE_HOST")
        if host:
            port = os.environ.get("ORACLE_PORT", "1521")
            service = dsn.split("/", 1)[1] if "/" in dsn else "FREEPDB1"
            dsn = f"{host}:{port}/{service}"
        _pool = oracledb.create_pool(
            user=cfg.get("database", "user", fallback="pdbadmin"),
            password=cfg.get("database", "password", fallback=""),
            dsn=dsn,
            # min=0: don't eagerly open connections before the PDB is mounted.
            # See Fix A/B/C in the GP postmortem (cold-start race that locked pdbadmin).
            min=cfg.getint("database", "pool.min", fallback=0),
            max=cfg.getint("database", "pool.max", fallback=4),
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
                SELECT knob_name, calibration_regime, sector_number, value, is_default
                FROM (
                    SELECT knob_name, calibration_regime, sector_number, value, is_default,
                           ROW_NUMBER() OVER (
                               PARTITION BY knob_name, calibration_regime, NVL(sector_number, -1)
                               -- Prefer a fitted value over a cold-start default (which
                               -- shares the sector=NULL slot for sector-wide knobs like
                               -- wear-rate), then the most recent. is_default first so a
                               -- real fit always wins regardless of when defaults were
                               -- (re)written.
                               ORDER BY is_default ASC, trained_at DESC
                           ) rn
                    FROM calibration_coefficients
                    WHERE track_id = :1
                )
                WHERE rn = 1
                """,
                [track_id],
            )
            # Track-wide pit_stop_time_loss provenance per regime, so we can borrow the
            # AI fit for PLAYER when PLAYER is still on the cold-start prior (below).
            pit_loss_is_default: dict[str, int] = {}
            pit_loss_value: dict[str, float] = {}
            for row in cursor:
                knob_name, regime, sector_number, value, is_default = row
                sector = sector_number if sector_number is not None else -1
                coefficients.put(knob_name, regime, sector, value)
                if knob_name == "pit_stop_time_loss" and sector == -1:
                    pit_loss_is_default[regime] = is_default
                    pit_loss_value[regime] = value

    # Pit-lane time loss is a near-deterministic property of the track (lane length +
    # speed limit), so PLAYER and AI lose essentially the same time. The PLAYER regime
    # rarely accumulates enough real pit stops (MIN_PIT_STOP_SAMPLES) in Free Practice to
    # fit, so when PLAYER is still a cold-start default but AI has a real fit, borrow the
    # AI value for PLAYER rather than simulating on the 22 s prior.
    player_default = pit_loss_is_default.get("PLAYER", 1)
    ai_default = pit_loss_is_default.get("AI", 1)
    if player_default and not ai_default and "AI" in pit_loss_value:
        coefficients.put("pit_stop_time_loss", "PLAYER", -1, pit_loss_value["AI"])

    return coefficients


_SELECT_TYRE_CURVES = """
    SELECT compound, regime, sector_number, tyre_age_laps, offset_ms
    FROM tyre_age_pace_offsets WHERE track_id = :1
"""


def load_tyre_curves_for_track(track_id: int) -> TyreCurves:
    curves = TyreCurves()
    pool = get_pool()
    with pool.acquire() as conn, conn.cursor() as cur:
        cur.execute(_SELECT_TYRE_CURVES, [track_id])
        for compound, regime, sector, age, offset_ms in cur:
            curves.put(int(compound), regime, int(sector), int(age), float(offset_ms))
    return curves


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
