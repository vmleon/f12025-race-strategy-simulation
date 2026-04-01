from __future__ import annotations

import logging

import oracledb

logger = logging.getLogger("simulator")


def dequeue_simulation_request(connection: oracledb.Connection, wait: int = 5):
    queue = connection.queue("PDBADMIN.SIMULATION_REQUEST", "JSON")
    queue.deqoptions.wait = wait
    try:
        message = queue.deqone()
        connection.commit()
        return message.payload if message else None
    except oracledb.DatabaseError as e:
        if e.args[0].code == 25228:  # timeout
            return None
        raise


def enqueue_simulation_result(connection: oracledb.Connection, payload: dict):
    queue = connection.queue("PDBADMIN.SIMULATION_RESULT", "JSON")
    queue.enqone(connection.msgproperties(payload=payload))
    connection.commit()


def dequeue_strategy_request(connection: oracledb.Connection, wait: int = 5):
    queue = connection.queue("PDBADMIN.STRATEGY_REQUEST", "JSON")
    queue.deqoptions.wait = wait
    try:
        message = queue.deqone()
        connection.commit()
        return message.payload if message else None
    except oracledb.DatabaseError as e:
        if e.args[0].code == 25228:  # timeout
            return None
        raise


def enqueue_strategy_result(connection: oracledb.Connection, payload: dict):
    queue = connection.queue("PDBADMIN.STRATEGY_RESULT", "JSON")
    queue.enqone(connection.msgproperties(payload=payload))
    connection.commit()
