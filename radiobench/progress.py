"""Lightweight progress wrapper: uses tqdm when available, else periodic prints."""
from __future__ import annotations

import sys


def track(iterable, total: int, desc: str):
    """Wrap an iterable with a live progress display (count, %, rate, ETA).

    Uses tqdm if installed; otherwise falls back to periodic stderr lines so the
    phases are never silent.
    """
    try:
        from tqdm import tqdm
        return tqdm(iterable, total=total, desc=desc, unit="call")
    except ImportError:
        return _PlainProgress(iterable, total, desc)


class _PlainProgress:
    def __init__(self, iterable, total, desc):
        self._it = iter(iterable)
        self._total = total
        self._desc = desc
        self._i = 0

    def __iter__(self):
        return self

    def __next__(self):
        item = next(self._it)  # propagates StopIteration at the end
        self._i += 1
        if self._i % 10 == 0 or self._i == self._total:
            pct = f" ({100 * self._i / self._total:.0f}%)" if self._total else ""
            print(f"{self._desc}: {self._i}/{self._total}{pct}", file=sys.stderr, flush=True)
        return item
