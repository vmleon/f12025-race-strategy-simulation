from radiobench.metrics import percentile, tts_estimate, extract_facts, fact_preservation


def test_percentile_basic():
    vals = [10, 20, 30, 40]
    assert percentile(vals, 50) == 25.0          # linear interpolation midpoint
    assert percentile(vals, 0) == 10
    assert percentile(vals, 100) == 40
    assert percentile([], 50) == 0.0


def test_tts_estimate_monotonic_with_length():
    short = tts_estimate("Box now.")
    longer = tts_estimate("Box now, P8, and watch the front-left for the next two laps.")
    assert longer > short > 0


def test_extract_facts_pulls_numbers_and_positions():
    facts = extract_facts("Box lap 12, P8, gap 1.7 seconds, 320 kph.")
    assert "12" in facts and "1.7" in facts and "320" in facts
    assert "P8" in facts


def test_fact_preservation_flags_dropped_and_invented():
    res = fact_preservation("Box lap 12, P8.", "Box now, P8.")  # dropped 12
    assert "12" in res["dropped"]
    assert res["invented"] == []

    res2 = fact_preservation("P8.", "P8, gap 2.5 seconds.")      # invented 2.5
    assert "2.5" in res2["invented"]
    assert res2["dropped"] == []

    res3 = fact_preservation("Box lap 12, P8.", "Lap 12 box, P8.")  # faithful
    assert res3["dropped"] == [] and res3["invented"] == []
