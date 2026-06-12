# radiobench

Offline test bench for the race-engineer **radio-message LLM renderer**. It runs the
exported radio dataset through candidate LLMs on a vLLM endpoint, measures generation
latency, scores the outputs with a panel of judge CLIs (Claude + Gemini + Grok), and
charts quality vs latency.

Three decoupled phases, run in order: **`generate` → `judge` → `report`**.

## Setup

The package has its own venv (`radiobench/venv`). To (re)create it:

```bash
python3 -m venv radiobench/venv
radiobench/venv/bin/pip install -r radiobench/requirements.txt
```

Requirements: a reachable vLLM OpenAI-compatible endpoint (see `config.yaml`), and the
`claude`, `gemini`, and `grok` CLIs installed and authenticated (used as judges).

**Always run from the repo root** so `radiobench` imports resolve.

## Commands

```bash
# 1. generate: run the sampled dataset rows through the candidate model(s), capture latency
radiobench/venv/bin/python -m radiobench generate

# 2. judge: score every generated output with each judge CLI (claude, gemini, grok)
radiobench/venv/bin/python -m radiobench judge

# 3. report: aggregate runs + judgements into a summary table + charts
radiobench/venv/bin/python -m radiobench report

# use a different config file (default: radiobench/config.yaml)
radiobench/venv/bin/python -m radiobench generate --config path/to/other.yaml

# run the unit tests
radiobench/venv/bin/python -m pytest radiobench/tests/ -v
```

All phases are **append-only and resumable**: re-running skips work already in the
result files, so you can Ctrl-C and continue, or run incrementally one model at a time.

## Configuration (`config.yaml`)

This is the iteration surface — edit it, then re-run; no code changes needed.

- **`dataset`** — path to the exported radio CSV (`database/exports/radio_dataset_*.csv`).
- **`sample`** — `size` (required), `stratify_by` (a CSV column, e.g. `priority`, or `null`
  for first-N), `seed` (reproducibility for the stratified random pick). To run **all**
  rows: `stratify_by: null` and `size:` a number ≥ the dataset size (e.g. `1000000`).
- **`candidates`** — list of OpenAI-compatible endpoints to test. Each has `name`,
  `base_url`, `model`, `temperature`, `max_tokens`.
- **`variants`** — the prompt variants. Each has a `system` prompt (persona + custom
  instructions + tone) and a `user_template` interpolating row fields, `{strategy_summary}`,
  and `{message_text}`. Add a variant to test a new tone/instruction; the bench runs the
  full `candidates × variants × rows` matrix.
- **`judges`** — the judge CLIs (`name` + `command`, e.g. `["claude", "-p"]`).
- **`judge`** — scoring `dimensions` (faithfulness, tone, concision, naturalness) and the
  per-call `timeout_s`.

## Typical workflow (one model at a time)

The GPU host usually fits only one model in memory, and the judges are cloud CLIs that
don't touch the GPU. So:

1. Load model **M** on the vLLM host; point a `candidates` entry at it.
2. `generate` → `judge` (scores M's outputs).
3. Swap to model **M+1** on the host, update the config, repeat.
4. `report` aggregates across every model run so far.

## Outputs (`radiobench/results/`, gitignored)

- `runs.jsonl` — one line per generated output (prompt, output, latency, tokens).
- `judgements.jsonl` — one line per (output × judge) with the rubric scores.
- `summary.md` — per (model, variant): overall + per-dimension scores, p50/p95 latency,
  invented-fact rate.
- `charts/quality_vs_latency.png` — the balance chart (p95 latency × overall score).
- `charts/score_breakdown.png` — scores by dimension.

## Cost note

Judging is the expensive part: `judge` makes one CLI call per output **per judge**
(outputs × 3). Keep `sample.size` modest while iterating on prompts; raise it once a
variant looks good. `generate` against vLLM is comparatively cheap but serial (~one model
call per row).
