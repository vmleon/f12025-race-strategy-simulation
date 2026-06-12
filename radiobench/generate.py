"""Generate phase: run candidates x variants x rows, yielding run records."""
from __future__ import annotations

from radiobench.candidate import generate_once
from radiobench.config import Candidate, Config, Variant
from radiobench.dataset import load_rows, sample_rows
from radiobench.jsonl import append_jsonl, done_keys
from radiobench.prompt import build_prompt


def generate_records(candidates: list[Candidate], variants: list[Variant], rows: list[dict],
                     done: set[tuple], gen_fn=None, timeout_s: int = 60):
    """Yield a run record for every (candidate, variant, row) not in `done`.

    `gen_fn(candidate, system, user, timeout_s) -> GenResult` is injectable for
    testing; defaults to the real streaming call.
    """
    if gen_fn is None:
        gen_fn = lambda c, s, u, t: generate_once(c, s, u, timeout_s=t)
    for cand in candidates:
        for variant in variants:
            for row in rows:
                row_id = row["message_id"]
                if (row_id, cand.name, variant.name) in done:
                    continue
                system, user = build_prompt(variant, row)
                r = gen_fn(cand, system, user, timeout_s)
                yield {
                    "row_id": row_id, "model": cand.name, "variant": variant.name,
                    "prompt_system": system, "prompt_user": user, "output": r.output,
                    "ttft_ms": round(r.ttft_ms, 1), "total_ms": round(r.total_ms, 1),
                    "completion_tokens": r.completion_tokens,
                    "tokens_per_sec": round(r.tokens_per_sec, 1), "error": r.error,
                }


def run_generate(config: Config, runs_path: str) -> int:
    """Generate and append run records to runs_path; return count written."""
    rows = sample_rows(load_rows(config.dataset), config.sample.size,
                       config.sample.stratify_by, config.sample.seed)
    done = done_keys(runs_path, ["row_id", "model", "variant"])
    n = 0
    for rec in generate_records(config.candidates, config.variants, rows, done):
        append_jsonl(runs_path, rec)
        n += 1
    return n
