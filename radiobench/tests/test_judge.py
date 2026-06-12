from radiobench.config import Judge
from radiobench.judge import build_judge_prompt, extract_scores, run_judge

DIMS = ["faithfulness", "tone", "concision", "naturalness"]


def test_build_judge_prompt_includes_parts_and_dimensions():
    p = build_judge_prompt(DIMS, "lap 12, P8", "Box this lap.", "Box now, P8.")
    assert "lap 12, P8" in p
    assert "Box this lap." in p
    assert "Box now, P8." in p
    for d in DIMS:
        assert d in p
    assert "JSON" in p


def test_extract_scores_parses_clean_json():
    out = '{"faithfulness":5,"tone":4,"concision":5,"naturalness":4,"rationale":"crisp"}'
    s = extract_scores(out, DIMS)
    assert s == {"faithfulness": 5, "tone": 4, "concision": 5, "naturalness": 4, "rationale": "crisp"}


def test_extract_scores_pulls_json_from_surrounding_prose():
    out = ('Here is my assessment:\n'
           '{"faithfulness": 3, "tone": 2, "concision": 4, "naturalness": 3, '
           '"rationale": "ok"}\nHope that helps!')
    s = extract_scores(out, DIMS)
    assert s["faithfulness"] == 3 and s["tone"] == 2


def test_extract_scores_returns_none_on_bad_output():
    assert extract_scores("no json here", DIMS) is None
    assert extract_scores('{"faithfulness": 9}', DIMS) is None  # out of range / missing dims


def test_run_judge_invokes_command_and_parses(monkeypatch):
    import subprocess

    def fake_run(cmd, **kwargs):
        assert cmd[:2] == ["claude", "-p"]
        assert isinstance(cmd[2], str) and cmd[2]  # prompt appended
        return subprocess.CompletedProcess(
            cmd, 0,
            stdout='{"faithfulness":4,"tone":4,"concision":4,"naturalness":4,"rationale":"good"}',
            stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)
    judge = Judge(name="claude", command=["claude", "-p"])
    res = run_judge(judge, "PROMPT", DIMS, timeout_s=10)
    assert res["faithfulness"] == 4 and res["error"] is None
