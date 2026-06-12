"""Judge panel: build a rubric prompt, invoke a judge CLI, extract JSON scores."""
from __future__ import annotations

import json
import re
import subprocess

from radiobench.config import Judge

_JSON_RE = re.compile(r"\{.*\}", re.DOTALL)


def build_judge_prompt(dimensions: list[str], context: str, original: str, candidate: str) -> str:
    dims = ", ".join(dimensions)
    keys = ", ".join(f'"{d}": <1-5>' for d in dimensions)
    return (
        "You are scoring a Formula 1 team-radio message rewritten by an AI for a driver.\n"
        f"Score it 1-5 on each of: {dims}.\n"
        "faithfulness = preserves all facts/intent with no invention; "
        "tone = sounds like a calm professional race engineer; "
        "concision = short enough to speak on radio; naturalness = fluent, human.\n\n"
        f"Race situation: {context}\n"
        f"Original message: {original}\n"
        f"AI rewrite to score: {candidate}\n\n"
        "Respond with ONLY a JSON object, no prose, of the form:\n"
        f'{{{keys}, "rationale": "<one short sentence>"}}'
    )


def extract_scores(stdout: str, dimensions: list[str]) -> dict | None:
    """Pull the JSON object from CLI stdout and validate dimension ints 1-5.

    Returns the parsed dict (scores + rationale) or None if absent/invalid.
    """
    matches = list(_JSON_RE.finditer(stdout))
    for m in reversed(matches):  # prefer the last JSON block
        try:
            obj = json.loads(m.group())
        except ValueError:
            continue
        if not isinstance(obj, dict):
            continue
        if all(isinstance(obj.get(d), int) and 1 <= obj[d] <= 5 for d in dimensions):
            return {**{d: obj[d] for d in dimensions}, "rationale": str(obj.get("rationale", ""))}
    return None


def run_judge(judge: Judge, prompt: str, dimensions: list[str], timeout_s: int) -> dict:
    """Invoke one judge CLI with the prompt as the final arg; parse its scores.

    Returns {<dims>, rationale, error}. On failure the dim scores are None and
    error is set.
    """
    null_scores = {d: None for d in dimensions}
    try:
        proc = subprocess.run(
            judge.command + [prompt],
            capture_output=True, text=True, timeout=timeout_s,
        )
    except subprocess.TimeoutExpired:
        return {**null_scores, "rationale": "", "error": "timeout"}
    if proc.returncode != 0:
        return {**null_scores, "rationale": "", "error": f"exit {proc.returncode}: {proc.stderr[:200]}"}
    scores = extract_scores(proc.stdout, dimensions)
    if scores is None:
        return {**null_scores, "rationale": "", "error": "unparseable"}
    return {**scores, "error": None}
