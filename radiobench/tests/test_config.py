import os
import tempfile
import textwrap

import pytest

from radiobench.config import load_config


def _write(tmp_yaml: str) -> str:
    f = tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False)
    f.write(textwrap.dedent(tmp_yaml))
    f.close()
    return f.name


_FIXTURE = """\
    dataset: x.csv
    output_dir: out
    sample:
      size: 10
      stratify_by: priority
      seed: 7
    candidates:
      - name: qwen72b
        base_url: http://h:8000/v1
        model: Qwen/Qwen2.5-72B-Instruct-AWQ
        temperature: 0.4
        max_tokens: 200
    variants:
      - name: calm_full
        system: You are a calm race engineer.
        user_template: 'Rewrite: "{message_text}"'
    judges:
      - name: claude
        command: ["claude", "-p"]
      - name: gemini
        command: ["gemini", "-p"]
    judge:
      dimensions: [faithfulness, tone, concision, naturalness]
      timeout_s: 30
      workers: 8
"""


def test_load_config_parses_fixture():
    path = _write(_FIXTURE)
    try:
        cfg = load_config(path)
        assert cfg.sample.size == 10
        assert cfg.sample.stratify_by == "priority"
        assert cfg.sample.seed == 7
        assert cfg.candidates[0].name == "qwen72b"
        assert cfg.candidates[0].base_url.endswith("/v1")
        assert cfg.variants[0].name == "calm_full"
        assert "{message_text}" in cfg.variants[0].user_template
        assert [j.name for j in cfg.judges] == ["claude", "gemini"]
        assert cfg.judges[0].command == ["claude", "-p"]
        assert cfg.judge.dimensions == ["faithfulness", "tone", "concision", "naturalness"]
        assert cfg.judge.workers == 8
    finally:
        os.remove(path)


def test_judge_workers_defaults_to_12_when_absent():
    path = _write(_FIXTURE.replace("      workers: 8\n", ""))
    try:
        cfg = load_config(path)
        assert cfg.judge.workers == 12
    finally:
        os.remove(path)


def test_shipped_config_loads_and_is_structurally_valid():
    # Loads the live config.yaml (which the user edits freely) — assert only that
    # it parses and has the required shape, not specific editable values.
    cfg = load_config("radiobench/config.yaml")
    assert cfg.dataset.endswith(".csv")
    assert isinstance(cfg.sample.size, int)
    assert len(cfg.candidates) >= 1 and cfg.candidates[0].base_url
    assert len(cfg.variants) >= 1 and "{message_text}" in cfg.variants[0].user_template
    assert len(cfg.judges) >= 1
    assert cfg.judge.dimensions


def test_load_config_rejects_missing_candidates():
    path = _write("dataset: x.csv\noutput_dir: o\nsample: {size: 1, stratify_by: null, seed: 1}\n")
    try:
        with pytest.raises(ValueError):
            load_config(path)
    finally:
        os.remove(path)
