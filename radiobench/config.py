"""Load and validate the test-bench YAML config into dataclasses."""
from __future__ import annotations

from dataclasses import dataclass

import yaml


@dataclass
class Candidate:
    name: str
    base_url: str
    model: str
    temperature: float = 0.4
    max_tokens: int = 200


@dataclass
class Variant:
    name: str
    system: str
    user_template: str


@dataclass
class Judge:
    name: str
    command: list[str]


@dataclass
class Sample:
    size: int
    stratify_by: str | None
    seed: int


@dataclass
class JudgeCfg:
    dimensions: list[str]
    timeout_s: int


@dataclass
class Config:
    dataset: str
    output_dir: str
    sample: Sample
    candidates: list[Candidate]
    variants: list[Variant]
    judges: list[Judge]
    judge: JudgeCfg


def load_config(path: str) -> Config:
    with open(path) as f:
        raw = yaml.safe_load(f)

    if not raw.get("candidates"):
        raise ValueError("config must define at least one candidate")
    if not raw.get("variants"):
        raise ValueError("config must define at least one variant")
    if not raw.get("judges"):
        raise ValueError("config must define at least one judge")

    s = raw["sample"]
    j = raw["judge"]
    return Config(
        dataset=raw["dataset"],
        output_dir=raw["output_dir"],
        sample=Sample(size=s["size"], stratify_by=s.get("stratify_by"), seed=s.get("seed", 42)),
        candidates=[Candidate(**c) for c in raw["candidates"]],
        variants=[Variant(**v) for v in raw["variants"]],
        judges=[Judge(**jd) for jd in raw["judges"]],
        judge=JudgeCfg(dimensions=j["dimensions"], timeout_s=j.get("timeout_s", 60)),
    )
