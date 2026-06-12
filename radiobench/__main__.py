"""radiobench CLI: generate | judge | report."""
from __future__ import annotations

import argparse
import os

from radiobench.config import load_config


def main(argv=None):
    parser = argparse.ArgumentParser(prog="radiobench")
    parser.add_argument("phase", choices=["generate", "judge", "report"])
    parser.add_argument("--config", default="radiobench/config.yaml")
    args = parser.parse_args(argv)

    cfg = load_config(args.config)
    runs_path = os.path.join(cfg.output_dir, "runs.jsonl")
    judgements_path = os.path.join(cfg.output_dir, "judgements.jsonl")

    if args.phase == "generate":
        from radiobench.generate import run_generate
        n = run_generate(cfg, runs_path)
        print(f"generate: wrote {n} run record(s) to {runs_path}")
    elif args.phase == "judge":
        from radiobench.judge import run_judge_phase
        n = run_judge_phase(cfg, runs_path, judgements_path)
        print(f"judge: wrote {n} judgement(s) to {judgements_path}")
    elif args.phase == "report":
        from radiobench.report import run_report
        out = run_report(cfg, runs_path, judgements_path)
        print(f"report: wrote {out}")


if __name__ == "__main__":
    main()
