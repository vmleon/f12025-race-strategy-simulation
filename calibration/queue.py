"""Oracle AQ helpers for calibration queue."""

from __future__ import annotations

import logging

import oracledb

logger = logging.getLogger("calibration")


def dequeue_calibration_request(connection: oracledb.Connection, wait: int = 5):
    queue = connection.queue("PDBADMIN.CALIBRATION_REQUEST", "JSON")
    queue.deqoptions.wait = wait
    try:
        message = queue.deqone()
        connection.commit()
        return message.payload if message else None
    except oracledb.DatabaseError as e:
        if e.args[0].code == 25228:  # timeout
            return None
        raise
