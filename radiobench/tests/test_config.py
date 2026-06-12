import pytest

from radiobench.config import load_config


def test_load_config_parses_sample_yaml():
    cfg = load_config("radiobench/config.yaml")
    assert cfg.dataset.endswith(".csv")
    assert cfg.sample.size == 10
    assert cfg.sample.stratify_by == "priority"
    assert cfg.candidates[0].name == "qwen72b"
    assert cfg.candidates[0].model == "Qwen/Qwen2.5-72B-Instruct-AWQ"
    assert cfg.candidates[0].base_url.endswith("/v1")
    assert cfg.variants[0].name == "calm_full"
    assert "race engineer" in cfg.variants[0].system
    assert "{message_text}" in cfg.variants[0].user_template
    assert [j.name for j in cfg.judges] == ["claude", "gemini", "grok"]
    assert cfg.judges[0].command == ["claude", "-p"]
    assert cfg.judge.dimensions == ["faithfulness", "tone", "concision", "naturalness"]


def test_load_config_rejects_missing_candidates():
    import tempfile, os
    bad = "dataset: x.csv\noutput_dir: o\nsample: {size: 1, stratify_by: null, seed: 1}\n"
    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
        f.write(bad)
        path = f.name
    try:
        with pytest.raises(ValueError):
            load_config(path)
    finally:
        os.remove(path)
