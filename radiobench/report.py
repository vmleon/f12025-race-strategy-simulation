"""Report phase: aggregate runs + judgements and render charts + a summary."""
from __future__ import annotations

import os
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")  # headless, no display
import matplotlib.pyplot as plt

from radiobench.config import Config
from radiobench.jsonl import read_jsonl
from radiobench.metrics import percentile, fact_preservation


def aggregate(runs: list[dict], judgements: list[dict], dimensions: list[str]) -> dict:
    """Aggregate per (model, variant): latency percentiles, judge scores, fact rates."""
    by_key = defaultdict(list)
    for r in runs:
        by_key[(r["model"], r["variant"])].append(r)

    # judge scores grouped by (model, variant, row_id) -> list of judge records
    judged = defaultdict(list)
    for j in judgements:
        if j.get("error") is not None:
            continue
        vals = [j[d] for d in dimensions if isinstance(j.get(d), (int, float))]
        if vals:
            judged[(j["model"], j["variant"], j["row_id"])].append(j)

    out = {}
    for key, rlist in by_key.items():
        totals = [r["total_ms"] for r in rlist if r.get("error") is None]
        ttfts = [r["ttft_ms"] for r in rlist if r.get("error") is None]
        invented = sum(1 for r in rlist
                       if r.get("error") is None
                       and fact_preservation(r["prompt_user"], r["output"])["invented"])

        dim_means: dict[str, list[float]] = {d: [] for d in dimensions}
        for r in rlist:
            judges_for_row = judged.get((key[0], key[1], r["row_id"]), [])
            for d in dimensions:
                scores = [jr[d] for jr in judges_for_row if isinstance(jr.get(d), (int, float))]
                if scores:
                    dim_means[d].append(sum(scores) / len(scores))

        per_dim = {d: (sum(v) / len(v) if v else None) for d, v in dim_means.items()}
        present = [per_dim[d] for d in dimensions if per_dim[d] is not None]
        out[key] = {
            "n_runs": len(rlist),
            "p50_total_ms": percentile(totals, 50),
            "p95_total_ms": percentile(totals, 95),
            "p50_ttft_ms": percentile(ttfts, 50),
            "overall": round(sum(present) / len(present), 4) if present else None,
            "fact_invented_rate": round(invented / len(rlist), 4) if rlist else 0.0,
            **{d: (round(per_dim[d], 4) if per_dim[d] is not None else None) for d in dimensions},
        }
    return out


def run_report(config: Config, runs_path: str, judgements_path: str) -> str:
    runs = read_jsonl(runs_path)
    judgements = read_jsonl(judgements_path)
    dims = config.judge.dimensions
    agg = aggregate(runs, judgements, dims)

    charts_dir = os.path.join(config.output_dir, "charts")
    os.makedirs(charts_dir, exist_ok=True)

    # Quality vs latency scatter (the balance chart).
    fig, ax = plt.subplots()
    for (model, variant), a in agg.items():
        if a["overall"] is not None:
            ax.scatter(a["p95_total_ms"], a["overall"])
            ax.annotate(f"{model}/{variant}", (a["p95_total_ms"], a["overall"]))
    ax.set_xlabel("p95 total latency (ms)")
    ax.set_ylabel("overall judge score (1-5)")
    ax.set_title("Quality vs latency")
    fig.savefig(os.path.join(charts_dir, "quality_vs_latency.png"), bbox_inches="tight")
    plt.close(fig)

    # Score breakdown by dimension.
    fig, ax = plt.subplots()
    labels = [f"{m}/{v}" for (m, v) in agg]
    for i, d in enumerate(dims):
        vals = [(agg[k][d] or 0) for k in agg]
        ax.bar([x + i * 0.2 for x in range(len(labels))], vals, width=0.2, label=d)
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, rotation=30, ha="right")
    ax.set_ylabel("score (1-5)")
    ax.set_title("Score by dimension")
    ax.legend()
    fig.savefig(os.path.join(charts_dir, "score_breakdown.png"), bbox_inches="tight")
    plt.close(fig)

    # Summary markdown table.
    lines = ["# Radio bench summary\n",
             "| model/variant | n | overall | " + " | ".join(dims)
             + " | p50 ms | p95 ms | invented rate |",
             "|---|---|---|" + "---|" * (len(dims) + 3)]
    for (m, v), a in agg.items():
        dim_cells = " | ".join(str(a[d]) for d in dims)
        lines.append(f"| {m}/{v} | {a['n_runs']} | {a['overall']} | {dim_cells} | "
                     f"{a['p50_total_ms']} | {a['p95_total_ms']} | {a['fact_invented_rate']} |")
    summary_path = os.path.join(config.output_dir, "summary.md")
    with open(summary_path, "w") as f:
        f.write("\n".join(lines) + "\n")

    return config.output_dir
