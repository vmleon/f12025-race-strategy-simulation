import json
import os
import tempfile

from radiobench.config import Candidate, Variant
from radiobench.candidate import GenResult
from radiobench.jsonl import append_jsonl, read_jsonl, done_keys
from radiobench.generate import generate_records


def test_jsonl_roundtrip_and_done_keys():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "runs.jsonl")
        append_jsonl(path, {"row_id": "1", "model": "m", "variant": "v", "output": "a"})
        append_jsonl(path, {"row_id": "2", "model": "m", "variant": "v", "output": "b"})
        rows = read_jsonl(path)
        assert [r["row_id"] for r in rows] == ["1", "2"]
        keys = done_keys(path, ["row_id", "model", "variant"])
        assert ("1", "m", "v") in keys and ("2", "m", "v") in keys
        assert done_keys("nonexistent.jsonl", ["row_id"]) == set()


def test_generate_records_skips_done_and_calls_model():
    cand = Candidate(name="m", base_url="http://h/v1", model="qwen", temperature=0.4, max_tokens=50)
    variant = Variant(name="v", system="sys", user_template='Say: "{message_text}"')
    rows = [{"message_id": "1", "message_text": "hi", "best_strategies": ""},
            {"message_id": "2", "message_text": "bye", "best_strategies": ""}]
    already = {("1", "m", "v")}

    calls = []

    def fake_gen(candidate, system, user, timeout_s):
        calls.append(user)
        return GenResult(output="OUT", ttft_ms=10, total_ms=20,
                         completion_tokens=2, tokens_per_sec=100)

    recs = list(generate_records([cand], [variant], rows, already, gen_fn=fake_gen))
    assert len(recs) == 1                       # row 1 skipped
    assert recs[0]["row_id"] == "2"
    assert recs[0]["model"] == "m" and recs[0]["variant"] == "v"
    assert recs[0]["output"] == "OUT" and recs[0]["total_ms"] == 20
    assert 'Say: "bye"' in calls[0]
