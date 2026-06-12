from radiobench.config import Variant
from radiobench.prompt import build_prompt


def _variant():
    return Variant(
        name="calm_full",
        system="You are a calm F1 race engineer.",
        user_template=("Situation: lap {lap_number}/{total_laps}, P{player_position}.\n"
                       "Strategy: {strategy_summary}\n"
                       'Rewrite: "{message_text}"'),
    )


def _row():
    return {
        "lap_number": "12", "total_laps": "44", "player_position": "8",
        "message_text": "Box this lap.", "circuit_name": "Catalunya",
        "best_strategies": '[{"rank":1,"label":"No stop","meanPosition":1.0}]',
    }


def test_build_prompt_interpolates_fields_and_strategy():
    system, user = build_prompt(_variant(), _row())
    assert system == "You are a calm F1 race engineer."
    assert "lap 12/44, P8" in user
    assert "Strategy: No stop (~P1.0)" in user
    assert 'Rewrite: "Box this lap."' in user


def test_build_prompt_blank_strategy_when_absent():
    row = _row()
    row["best_strategies"] = ""
    _, user = build_prompt(_variant(), row)
    assert "Strategy: \n" in user


def test_build_prompt_tolerates_missing_field():
    v = Variant(name="x", system="s", user_template="P{player_position} {missing_field}")
    _, user = build_prompt(v, {"player_position": "5", "best_strategies": ""})
    assert user == "P5 "  # missing field renders empty, no crash
