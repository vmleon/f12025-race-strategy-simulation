"""Judge panel: build a rubric prompt, invoke a judge CLI, extract JSON scores."""
from __future__ import annotations

import json
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed

from radiobench.config import Config, Judge
from radiobench.jsonl import append_jsonl, read_jsonl, done_keys
from radiobench.progress import track

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


def run_judge_phase(config: Config, runs_path: str, judgements_path: str,
                    judge_fn=None, workers: int | None = None) -> int:
    """Score every (run output, judge) pair not already recorded and append to
    judgements_path. Returns the count written.

    Each judge CLI is a slow subprocess, so the pairs run concurrently in a thread
    pool (``config.judge.workers``); results are appended in the main thread as they
    complete (thread-safe), with a live progress bar. Each pair is scored once with
    one retry on failure.

    `judge_fn(judge, prompt, dimensions, timeout_s) -> dict` is injectable for tests.
    """
    if judge_fn is None:
        judge_fn = run_judge
    if workers is None:
        workers = config.judge.workers
    dims = config.judge.dimensions
    done = done_keys(judgements_path, ["row_id", "model", "variant", "judge"])

    tasks = []
    for run in read_jsonl(runs_path):
        if run.get("error") is not None or not run.get("output"):
            continue
        for judge in config.judges:
            if (run["row_id"], run["model"], run["variant"], judge.name) not in done:
                tasks.append((run, judge))

    if not tasks:
        return 0

    def score(task):
        run, judge = task
        context = run.get("prompt_user", "")
        original = run.get("original", context)  # fall back to context for older records
        prompt = build_judge_prompt(dims, context, original, run["output"])
        res = judge_fn(judge, prompt, dims, config.judge.timeout_s)
        if res.get("error") is not None:  # one retry
            res = judge_fn(judge, prompt, dims, config.judge.timeout_s)
        return {
            "row_id": run["row_id"], "model": run["model"], "variant": run["variant"],
            "judge": judge.name, **{d: res.get(d) for d in dims},
            "rationale": res.get("rationale", ""), "error": res.get("error"),
        }

    n = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(score, t) for t in tasks]
        for fut in track(as_completed(futures), total=len(tasks), desc="judge"):
            append_jsonl(judgements_path, fut.result())
            n += 1
    return n
