"""Objective metrics: latency percentiles, TTS length, and fact preservation."""
from __future__ import annotations

import re

# Position tokens like P8 / p12, and bare numbers incl. decimals.
_POS_RE = re.compile(r"\bP\d+\b", re.IGNORECASE)
_NUM_RE = re.compile(r"\d+(?:\.\d+)?")


def percentile(values: list[float], p: float) -> float:
    """Linear-interpolation percentile (p in [0,100]); 0.0 for empty input."""
    if not values:
        return 0.0
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    rank = (p / 100) * (len(s) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(s) - 1)
    frac = rank - lo
    return float(s[lo] + (s[hi] - s[lo]) * frac)


def tts_estimate(text: str) -> float:
    """Rough spoken duration in seconds (~2.5 words/sec)."""
    return len(text.split()) / 2.5


def extract_facts(text: str) -> set[str]:
    """Position tokens (normalised upper-case, e.g. P8) and numeric tokens."""
    facts = {m.group().upper() for m in _POS_RE.finditer(text)}
    pos_spans = [m.span() for m in _POS_RE.finditer(text)]

    def _in_pos(span):
        return any(a <= span[0] < b for a, b in pos_spans)

    for m in _NUM_RE.finditer(text):
        if not _in_pos(m.span()):
            facts.add(m.group())
    return facts


def fact_preservation(input_text: str, output_text: str) -> dict:
    """Compare fact tokens between input and output.

    Returns {"dropped": [...], "invented": [...]} — facts in input missing from
    output, and facts in output absent from input (likely hallucination).
    """
    src = extract_facts(input_text)
    out = extract_facts(output_text)
    return {
        "dropped": sorted(src - out),
        "invented": sorted(out - src),
    }
