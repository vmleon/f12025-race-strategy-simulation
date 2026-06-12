"""Append-only JSONL helpers used by every phase for resumable results."""
from __future__ import annotations

import json
import os


def append_jsonl(path: str, record: dict) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "a") as f:
        f.write(json.dumps(record) + "\n")


def read_jsonl(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def done_keys(path: str, key_fields: list[str]) -> set[tuple]:
    """Set of value-tuples of key_fields already present in the JSONL file."""
    return {tuple(r.get(k) for k in key_fields) for r in read_jsonl(path)}
