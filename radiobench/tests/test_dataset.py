from radiobench.dataset import strategy_summary, sample_rows


def test_strategy_summary_renders_top_labels():
    js = '[{"rank":1,"label":"No stop","meanPosition":1.03,"expectedPoints":25.0}]'
    assert strategy_summary(js) == "No stop (~P1.0)"


def test_strategy_summary_top_two_sorted_by_rank():
    js = ('[{"rank":2,"label":"1-stop M","meanPosition":1.2,"expectedPoints":22},'
          '{"rank":1,"label":"No stop","meanPosition":1.0,"expectedPoints":25}]')
    assert strategy_summary(js) == "No stop (~P1.0); 1-stop M (~P1.2)"


def test_strategy_summary_empty_or_invalid_returns_blank():
    assert strategy_summary("") == ""
    assert strategy_summary("   ") == ""
    assert strategy_summary("not json") == ""
    assert strategy_summary("[]") == ""


def test_sample_rows_stratified_is_proportional_and_deterministic():
    rows = ([{"priority": "NORMAL"}] * 80
            + [{"priority": "HIGH"}] * 16
            + [{"priority": "IMMEDIATE"}] * 4)
    out = sample_rows(rows, size=10, stratify_by="priority", seed=42)
    counts = {}
    for r in out:
        counts[r["priority"]] = counts.get(r["priority"], 0) + 1
    assert sum(counts.values()) == 10
    assert counts.get("NORMAL", 0) == 8   # 80% of 10
    assert counts.get("HIGH", 0) == 2     # 16% -> rounds to 2
    assert sample_rows(rows, 10, "priority", 42) == out


def test_sample_rows_first_n_when_no_stratify():
    rows = [{"i": i} for i in range(20)]
    out = sample_rows(rows, size=3, stratify_by=None, seed=42)
    assert out == rows[:3]
