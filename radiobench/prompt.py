"""Build the (system, user) prompt for one dataset row under a variant."""
from __future__ import annotations

from radiobench.config import Variant
from radiobench.dataset import strategy_summary


class _SafeDict(dict):
    """Missing template fields render as empty strings instead of raising."""

    def __missing__(self, key):
        return ""


def build_prompt(variant: Variant, row: dict) -> tuple[str, str]:
    ctx = _SafeDict(row)
    ctx["strategy_summary"] = strategy_summary(row.get("best_strategies", ""))
    user = variant.user_template.format_map(ctx)
    return variant.system, user
