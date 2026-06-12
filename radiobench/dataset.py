"""Load the exported radio CSV, summarise strategy JSON, and sample rows."""
from __future__ import annotations

import csv
import json
import random


def load_rows(csv_path: str) -> list[dict]:
    with open(csv_path, newline="") as f:
        return list(csv.DictReader(f))


def strategy_summary(best_strategies_json: str) -> str:
    """Render the best_strategies JSON array as a short readable phrase.

    Returns "" for empty/invalid/empty-array input. Shows the top two by rank,
    each as "<label> (~P<meanPosition>)".
    """
    s = (best_strategies_json or "").strip()
    if not s:
        return ""
    try:
        arr = json.loads(s)
    except (ValueError, TypeError):
        return ""
    if not isinstance(arr, list) or not arr:
        return ""
    parts = []
    for item in sorted(arr, key=lambda x: x.get("rank", 99))[:2]:
        label = item.get("label", "?")
        mp = item.get("meanPosition")
        parts.append(f"{label} (~P{mp:.1f})" if isinstance(mp, (int, float)) else str(label))
    return "; ".join(parts)


def sample_rows(rows: list[dict], size: int, stratify_by: str | None, seed: int) -> list[dict]:
    """Pick `size` rows. With stratify_by, allocate proportionally per group
    (deterministic under seed). Without it, return the first `size` rows."""
    if stratify_by is None:
        return rows[:size]

    groups: dict[str, list[dict]] = {}
    for r in rows:
        groups.setdefault(r.get(stratify_by), []).append(r)

    total = len(rows)
    rng = random.Random(seed)
    out: list[dict] = []
    for key in sorted(groups, key=lambda k: (k is None, str(k))):
        group = groups[key]
        take = round(size * len(group) / total)
        take = min(take, len(group))
        out.extend(rng.sample(group, take) if take < len(group) else list(group))

    if len(out) > size:
        out = out[:size]
    elif len(out) < size:
        remaining = [r for r in rows if r not in out]
        out.extend(remaining[: size - len(out)])
    return out
