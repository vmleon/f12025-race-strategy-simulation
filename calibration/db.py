from __future__ import annotations

import configparser
import os
from datetime import datetime

import oracledb

from calibration.cold_start import KNOB_DEFAULTS, METHOD_NAME, REGIMES
from calibration.outlier_detector import SectorEntry, SectorKey

_pool: oracledb.ConnectionPool | None = None

_CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config.properties")


def _read_config() -> configparser.ConfigParser:
    config = configparser.ConfigParser()
    config.read(_CONFIG_PATH)
    return config


def get_pool() -> oracledb.ConnectionPool:
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
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None


# ── cold start check ────────────────────────────────────────────────

def has_default_coefficients(conn: oracledb.Connection, track_id: int) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) FROM calibration_coefficients WHERE track_id = :1 AND is_default = 1",
            [track_id],
        )
        return cur.fetchone()[0] > 0


def ensure_cold_start_defaults(conn: oracledb.Connection, track_id: int) -> int:
    """Ensure each (regime, knob) has an is_default row matching the current
    KNOB_DEFAULTS. Inserts missing rows and refreshes any whose stored value has
    drifted from the code default (e.g. after a units change), so the cold-start
    prior in the DB always tracks the code. Returns the number of rows changed."""
    now = datetime.now()
    changed = 0
    with conn.cursor() as cur:
        for regime in REGIMES:
            for knob_name, value in KNOB_DEFAULTS:
                cur.execute(
                    "SELECT value FROM calibration_coefficients "
                    "WHERE track_id = :1 AND knob_name = :2 AND calibration_regime = :3 "
                    "  AND sector_number IS NULL AND method_name = :4 AND is_default = 1",
                    [track_id, knob_name, regime, METHOD_NAME],
                )
                row = cur.fetchone()
                if row is None:
                    insert_calibration_coefficient(
                        conn, track_id, knob_name, regime, None, METHOD_NAME,
                        value, 1, now,
                    )
                    changed += 1
                elif abs(float(row[0]) - value) > 1e-9:
                    cur.execute(
                        "UPDATE calibration_coefficients SET value = :1, trained_at = :2 "
                        "WHERE track_id = :3 AND knob_name = :4 AND calibration_regime = :5 "
                        "  AND sector_number IS NULL AND method_name = :6 AND is_default = 1",
                        [value, now, track_id, knob_name, regime, METHOD_NAME],
                    )
                    changed += 1
    return changed


# ── outlier detection queries ────────────────────────────────────────

_SELECT_SECTORS_FOR_OUTLIER_DETECTION = """
    SELECT ss.session_uid, ss.car_index, ss.lap_number, ss.sector_number,
           ss.sector_time_ms, p.driver_name, s.track_id,
           ss.tyre_compound_visual, p.ai_controlled, ss.weather
    FROM sector_snapshots ss
    JOIN participants p ON p.session_uid = ss.session_uid AND p.car_index = ss.car_index
    JOIN sessions s ON s.session_uid = ss.session_uid
    WHERE s.track_id = :1
      AND ss.lap_invalid = 0
      AND ss.pit_status = 0
      AND ss.safety_car_status = 0
      AND NOT (ss.lap_number = 1 AND ss.sector_number = 0
               AND s.session_type IN (10, 11, 12, 15, 16, 17))
      AND ss.sector_time_ms > 0
      AND ss.front_wing_damage_l = 0 AND ss.front_wing_damage_r = 0
      AND ss.rear_wing_damage = 0 AND ss.floor_damage = 0
      AND ss.diffuser_damage = 0 AND ss.sidepod_damage = 0
      AND ss.engine_damage = 0 AND ss.gearbox_damage = 0
    ORDER BY p.driver_name, ss.sector_number, ss.tyre_compound_visual
"""


def get_sectors_for_outlier_detection(conn: oracledb.Connection, track_id: int) -> list[SectorEntry]:
    with conn.cursor() as cur:
        cur.execute(_SELECT_SECTORS_FOR_OUTLIER_DETECTION, [track_id])
        return [
            SectorEntry(
                session_uid=row[0], car_index=row[1], lap_number=row[2],
                sector_number=row[3], sector_time_ms=row[4], driver_name=row[5],
                track_id=row[6], tyre_compound_visual=row[7], ai_controlled=row[8] == 1,
                weather=row[9],
            )
            for row in cur
        ]


# ── outlier flag updates ─────────────────────────────────────────────

def update_outlier_flags(conn: oracledb.Connection, track_id: int, outliers: list[SectorKey]) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """UPDATE sector_snapshots SET outlier = 0
               WHERE session_uid IN (SELECT session_uid FROM sessions WHERE track_id = :1)
                 AND outlier = 1""",
            [track_id],
        )
        if outliers:
            cur.executemany(
                """UPDATE sector_snapshots SET outlier = 1
                   WHERE session_uid = :1 AND car_index = :2 AND lap_number = :3 AND sector_number = :4""",
                [(o.session_uid, o.car_index, o.lap_number, o.sector_number) for o in outliers],
            )


# ── pit stop data ────────────────────────────────────────────────────

_SELECT_PIT_STOP_SECTORS = """
    SELECT ss.session_uid, ss.car_index, ss.lap_number, ss.sector_number,
           ss.sector_time_ms, ss.pit_status, ss.tyre_compound_actual,
           p.ai_controlled
    FROM sector_snapshots ss
    JOIN participants p ON p.session_uid = ss.session_uid AND p.car_index = ss.car_index
    JOIN sessions s ON s.session_uid = ss.session_uid
    WHERE s.track_id = :1
      AND ss.pit_status IN (1, 2)
      AND ss.sector_time_ms > 0
      AND ss.lap_number > 1
      AND ss.safety_car_status = 0
      AND ss.penalties_sec = 0
    ORDER BY ss.car_index, ss.lap_number, ss.sector_number
"""

PIT_COL_SESSION = 0
PIT_COL_CAR = 1
PIT_COL_LAP = 2
PIT_COL_SECTOR = 3
PIT_COL_TIME = 4
PIT_COL_STATUS = 5
PIT_COL_COMPOUND = 6
PIT_COL_AI = 7


def get_pit_stop_sectors(conn: oracledb.Connection, track_id: int) -> list[tuple]:
    with conn.cursor() as cur:
        cur.execute(_SELECT_PIT_STOP_SECTORS, [track_id])
        return cur.fetchall()


_SELECT_NORMAL_SECTOR_MEDIANS = """
    SELECT ss.sector_number, p.ai_controlled, MEDIAN(ss.sector_time_ms)
    FROM sector_snapshots ss
    JOIN participants p ON p.session_uid = ss.session_uid AND p.car_index = ss.car_index
    JOIN sessions s ON s.session_uid = ss.session_uid
    WHERE s.track_id = :1
      AND ss.pit_status = 0
      AND ss.lap_invalid = 0
      AND ss.safety_car_status = 0
      AND ss.lap_number > 1
      AND ss.sector_time_ms > 0
      AND ss.outlier = 0
    GROUP BY ss.sector_number, p.ai_controlled
"""


def get_normal_sector_medians(conn: oracledb.Connection, track_id: int) -> dict[tuple[int, int], float]:
    """Returns {(sector_number, ai_controlled): median_sector_time_ms}."""
    with conn.cursor() as cur:
        cur.execute(_SELECT_NORMAL_SECTOR_MEDIANS, [track_id])
        return {(row[0], row[1]): float(row[2]) for row in cur}


# ── calibration data ─────────────────────────────────────────────────

_SELECT_CALIBRATION_DATA = """
    SELECT ss.session_uid, ss.car_index, ss.lap_number, ss.sector_number,
           ss.sector_time_ms,
           ss.tyre_compound_visual, ss.tyre_age_laps,
           ss.fuel_in_tank_kg,
           ss.gap_to_car_ahead_ms,
           ss.drs_allowed,
           ss.weather, ss.track_temp, ss.air_temp,
           ss.front_wing_damage_l, ss.front_wing_damage_r, ss.rear_wing_damage,
           ss.floor_damage, ss.diffuser_damage, ss.sidepod_damage,
           ss.engine_damage, ss.gearbox_damage,
           ss.tyre_surface_temp_rl, ss.tyre_surface_temp_rr,
           ss.tyre_surface_temp_fl, ss.tyre_surface_temp_fr,
           ss.tyre_inner_temp_rl, ss.tyre_inner_temp_rr,
           ss.tyre_inner_temp_fl, ss.tyre_inner_temp_fr,
           ss.tyre_wear_fl, ss.tyre_wear_fr, ss.tyre_wear_rl, ss.tyre_wear_rr
    FROM sector_snapshots ss
    JOIN participants p ON p.session_uid = ss.session_uid AND p.car_index = ss.car_index
    JOIN sessions s ON s.session_uid = ss.session_uid
    WHERE s.track_id = :1
      AND p.ai_controlled = :2
      AND ss.lap_invalid = 0
      AND ss.corner_cutting_warnings = 0
      AND ss.pit_status = 0
      AND ss.safety_car_status = 0
      AND NOT (ss.lap_number = 1 AND ss.sector_number = 0
               AND s.session_type IN (10, 11, 12, 15, 16, 17))
      AND ss.outlier = 0
      AND ss.sector_time_ms > 0
    ORDER BY ss.car_index, ss.lap_number, ss.sector_number
"""

# Column indices for calibration data rows
_COL_SESSION_UID = 0
_COL_CAR_INDEX = 1
_COL_LAP_NUMBER = 2
_COL_SECTOR_NUMBER = 3
_COL_SECTOR_TIME_MS = 4
_COL_TYRE_COMPOUND = 5
_COL_TYRE_AGE = 6
_COL_FUEL = 7
_COL_GAP_AHEAD = 8
_COL_DRS = 9
_COL_WEATHER = 10
_COL_TRACK_TEMP = 11
_COL_AIR_TEMP = 12
_COL_FW_DMG_L = 13
_COL_FW_DMG_R = 14
_COL_RW_DMG = 15
_COL_FLOOR_DMG = 16
_COL_DIFF_DMG = 17
_COL_SIDE_DMG = 18
_COL_ENG_DMG = 19
_COL_GEAR_DMG = 20
# tyre surface/inner temps occupy 21–28; per-wheel wear % follows.
_COL_WEAR_FL = 29
_COL_WEAR_FR = 30
_COL_WEAR_RL = 31
_COL_WEAR_RR = 32


def get_calibration_data(conn: oracledb.Connection, track_id: int, regime: str) -> list[tuple]:
    ai_controlled = 1 if regime == "AI" else 0
    with conn.cursor() as cur:
        cur.execute(_SELECT_CALIBRATION_DATA, [track_id, ai_controlled])
        return cur.fetchall()


# ── coefficient insert ───────────────────────────────────────────────

_INSERT_COEFFICIENT = """
    INSERT INTO calibration_coefficients (
        coefficient_id, track_id, knob_name, calibration_regime,
        sector_number, method_name, value, is_default, trained_at
    ) VALUES (seq_calibration_coefficients.NEXTVAL, :1,:2,:3,:4,:5,:6,:7,:8)
"""


def insert_calibration_coefficient(
    conn: oracledb.Connection, track_id: int, knob_name: str, regime: str,
    sector_number: int | None, method_name: str, value: float,
    is_default: int, trained_at: datetime,
) -> None:
    with conn.cursor() as cur:
        cur.execute(_INSERT_COEFFICIENT, [
            track_id, knob_name, regime, sector_number, method_name,
            value, is_default, trained_at,
        ])
