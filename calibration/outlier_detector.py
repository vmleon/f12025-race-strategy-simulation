from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from math import floor, ceil

MIN_SAMPLES_FOR_IQR = 10
AI_MULTIPLIER = 1.5
HUMAN_MULTIPLIER = 2.0


@dataclass(frozen=True)
class SectorKey:
    session_uid: int
    car_index: int
    lap_number: int
    sector_number: int


@dataclass(frozen=True)
class SectorEntry:
    session_uid: int
    car_index: int
    lap_number: int
    sector_number: int
    sector_time_ms: int
    driver_name: str
    track_id: int
    tyre_compound_visual: int
    ai_controlled: bool
    weather: int = 0


def weather_category(weather: int) -> str:
    """Map F1 2025 weather code to dry/wet category.

    0=clear, 1=light cloud, 2=overcast, 3=light rain, 4=heavy rain, 5=storm.
    """
    return "wet" if weather >= 3 else "dry"


def detect_outliers(entries: list[SectorEntry]) -> list[SectorKey]:
    if not entries:
        return []

    groups: dict[str, list[SectorEntry]] = defaultdict(list)
    for e in entries:
        key = f"{e.driver_name}|{e.track_id}|{e.sector_number}|{e.tyre_compound_visual}|{weather_category(e.weather)}"
        groups[key].append(e)

    outliers: list[SectorKey] = []
    for group in groups.values():
        if len(group) >= MIN_SAMPLES_FOR_IQR:
            outliers.extend(_detect_by_iqr(group))
    return outliers


def _detect_by_iqr(group: list[SectorEntry]) -> list[SectorKey]:
    times = sorted(e.sector_time_ms for e in group)

    q1 = _percentile(times, 25)
    q3 = _percentile(times, 75)
    iqr = q3 - q1

    if iqr == 0:
        return []

    multiplier = AI_MULTIPLIER if group[0].ai_controlled else HUMAN_MULTIPLIER
    lower_fence = q1 - multiplier * iqr
    upper_fence = q3 + multiplier * iqr

    return [
        SectorKey(e.session_uid, e.car_index, e.lap_number, e.sector_number)
        for e in group
        if e.sector_time_ms < lower_fence or e.sector_time_ms > upper_fence
    ]


def _percentile(sorted_values: list[int], pct: int) -> int:
    if len(sorted_values) == 1:
        return sorted_values[0]
    index = pct / 100.0 * (len(sorted_values) - 1)
    lower = int(floor(index))
    upper = int(ceil(index))
    if lower == upper:
        return sorted_values[lower]
    fraction = index - lower
    return round(sorted_values[lower] + fraction * (sorted_values[upper] - sorted_values[lower]))


def _median(values: list[int]) -> int:
    sorted_values = sorted(values)
    return _percentile(sorted_values, 50)
