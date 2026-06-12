import tempfile, os

from radiobench.config import Config, Sample, JudgeCfg, Judge
from radiobench.jsonl import append_jsonl, read_jsonl
from radiobench.judge import run_judge_phase

DIMS = ["faithfulness", "tone", "concision", "naturalness"]


def test_run_judge_phase_judges_each_output_with_each_judge_and_skips_done():
    with tempfile.TemporaryDirectory() as d:
        runs = os.path.join(d, "runs.jsonl")
        judg = os.path.join(d, "judgements.jsonl")
        append_jsonl(runs, {"row_id": "1", "model": "m", "variant": "v",
                            "prompt_user": "lap 12, P8", "output": "Box now, P8.", "error": None})
        # one judgement already present (claude) -> should be skipped
        append_jsonl(judg, {"row_id": "1", "model": "m", "variant": "v", "judge": "claude",
                            "faithfulness": 5, "tone": 5, "concision": 5, "naturalness": 5,
                            "error": None})

        cfg = Config(dataset="x", output_dir=d, sample=Sample(1, None, 1),
                     candidates=[], variants=[],
                     judges=[Judge("claude", ["claude", "-p"]), Judge("gemini", ["gemini", "-p"])],
                     judge=JudgeCfg(DIMS, 10))

        def fake_run_judge(judge, prompt, dimensions, timeout_s):
            return {"faithfulness": 4, "tone": 4, "concision": 4, "naturalness": 4,
                    "rationale": "ok", "error": None}

        n = run_judge_phase(cfg, runs, judg, judge_fn=fake_run_judge)
        assert n == 1                       # only gemini judged (claude already done)
        rows = read_jsonl(judg)
        assert {r["judge"] for r in rows} == {"claude", "gemini"}
        gemini = [r for r in rows if r["judge"] == "gemini"][0]
        assert gemini["faithfulness"] == 4 and gemini["row_id"] == "1"
