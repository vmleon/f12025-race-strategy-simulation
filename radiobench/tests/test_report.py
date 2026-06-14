import os
import tempfile

from radiobench.report import aggregate, run_report
from radiobench.config import Config, Sample, JudgeCfg


def test_aggregate_computes_latency_and_scores():
    runs = [
        {"row_id": "1", "model": "m", "variant": "v", "total_ms": 100, "ttft_ms": 40,
         "tokens_per_sec": 50, "prompt_user": "Box lap 12, P8.", "output": "Box now, P8.",
         "error": None},
        {"row_id": "2", "model": "m", "variant": "v", "total_ms": 300, "ttft_ms": 60,
         "tokens_per_sec": 30, "prompt_user": "P5.", "output": "P5, gap 2.0 seconds.",
         "error": None},
    ]
    judgements = [
        {"row_id": "1", "model": "m", "variant": "v", "judge": "claude",
         "faithfulness": 5, "tone": 4, "concision": 5, "naturalness": 4, "error": None},
        {"row_id": "1", "model": "m", "variant": "v", "judge": "gemini",
         "faithfulness": 3, "tone": 4, "concision": 5, "naturalness": 4, "error": None},
    ]
    dims = ["faithfulness", "tone", "concision", "naturalness"]
    agg = aggregate(runs, judgements, dims)
    key = ("m", "v")
    assert agg[key]["n_runs"] == 2
    assert agg[key]["p50_total_ms"] == 200.0
    assert agg[key]["overall"] == 4.25            # mean of the four per-dim means on row 1
    assert agg[key]["faithfulness"] == 4.0        # (5+3)/2
    assert agg[key]["fact_invented_rate"] == 0.5  # row 2 invented "2.0"


def test_run_report_writes_summary_and_chart(tmp_path):
    runs_p = tmp_path / "runs.jsonl"
    judg_p = tmp_path / "judgements.jsonl"
    runs_p.write_text(
        '{"row_id":"1","model":"m","variant":"v","total_ms":120,"ttft_ms":40,'
        '"tokens_per_sec":50,"prompt_user":"P8.","output":"P8.","error":null}\n')
    judg_p.write_text(
        '{"row_id":"1","model":"m","variant":"v","judge":"claude","faithfulness":5,'
        '"tone":5,"concision":5,"naturalness":5,"error":null}\n')
    cfg = Config(dataset="x", output_dir=str(tmp_path), sample=Sample(1, None, 1),
                 candidates=[], variants=[], judges=[],
                 judge=JudgeCfg(["faithfulness", "tone", "concision", "naturalness"], 60))
    out_dir = run_report(cfg, str(runs_p), str(judg_p))
    assert os.path.exists(os.path.join(out_dir, "summary.md"))
    assert os.path.exists(os.path.join(out_dir, "charts", "quality_vs_latency.png"))
    charts = os.path.join(out_dir, "charts")
    assert os.path.exists(os.path.join(charts, "faithfulness_vs_latency.png"))
    assert os.path.exists(os.path.join(charts, "dim_faithfulness.png"))
    assert os.path.exists(os.path.join(charts, "dim_tone.png"))
    assert os.path.exists(os.path.join(charts, "dim_concision.png"))
    assert os.path.exists(os.path.join(charts, "dim_naturalness.png"))
    assert os.path.exists(os.path.join(charts, "dim_invented_rate.png"))
