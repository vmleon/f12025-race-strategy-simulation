from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from math import floor, ceil

MIN_SAMPLES_FOR_IQR = 10
AI_MULTIPLIER = 1.5
HUMAN_MULTIPLIER = 2.0
DEFAULT_SKILL_RATING = 50


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
    tyre_compound_actual: int
    ai_controlled: bool
    weather: int = 0


def weather_category(weather: int) -> str:
    """Map F1 2025 weather code to dry/wet category.

    0=clear, 1=light cloud, 2=overcast, 3=light rain, 4=heavy rain, 5=storm.
    """
    return "wet" if weather >= 3 else "dry"


@dataclass(frozen=True)
class DriverRating:
    driver_name: str
    track_id: int
    skill_rating: int


def detect_outliers(entries: list[SectorEntry], driver_ratings: list[DriverRating]) -> list[SectorKey]:
    if not entries:
        return []

    rating_index = _index_ratings(driver_ratings)

    groups: dict[str, list[SectorEntry]] = defaultdict(list)
    for e in entries:
        key = f"{e.driver_name}|{e.track_id}|{e.sector_number}|{e.tyre_compound_actual}|{weather_category(e.weather)}"
        groups[key].append(e)

    cross_driver_medians = _compute_cross_driver_medians(entries)

    outliers: list[SectorKey] = []
    for group in groups.values():
        if not group:
            continue
        if len(group) >= MIN_SAMPLES_FOR_IQR:
            outliers.extend(_detect_by_iqr(group))
        else:
            outliers.extend(_detect_by_cold_start(group, rating_index, cross_driver_medians))

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


def _detect_by_cold_start(
    group: list[SectorEntry],
    rating_index: dict[str, dict[int, int]],
    cross_driver_medians: dict[str, int],
) -> list[SectorKey]:
    sample = group[0]
    skill_rating = _lookup_skill_rating(rating_index, sample.driver_name, sample.track_id)
    median_key = f"{sample.track_id}|{sample.sector_number}|{sample.tyre_compound_actual}|{weather_category(sample.weather)}"
    reference_median = cross_driver_medians.get(median_key)

    if reference_median is None:
        return []

    tolerance_ms = 1500.0 * (110 - skill_rating) / 60.0

    return [
        SectorKey(e.session_uid, e.car_index, e.lap_number, e.sector_number)
        for e in group
        if e.sector_time_ms > reference_median + tolerance_ms
    ]


def _lookup_skill_rating(rating_index: dict[str, dict[int, int]], driver_name: str, track_id: int) -> int:
    tracks = rating_index.get(driver_name)
    if tracks is None:
        return DEFAULT_SKILL_RATING
    rating = tracks.get(track_id)
    if rating is not None:
        return rating
    rating = tracks.get(-1)
    return rating if rating is not None else DEFAULT_SKILL_RATING


def _index_ratings(ratings: list[DriverRating]) -> dict[str, dict[int, int]]:
    index: dict[str, dict[int, int]] = defaultdict(dict)
    for r in ratings:
        index[r.driver_name][r.track_id] = r.skill_rating
    return dict(index)


def _compute_cross_driver_medians(entries: list[SectorEntry]) -> dict[str, int]:
    grouped: dict[str, list[int]] = defaultdict(list)
    for e in entries:
        key = f"{e.track_id}|{e.sector_number}|{e.tyre_compound_actual}|{weather_category(e.weather)}"
        grouped[key].append(e.sector_time_ms)
    return {key: _median(times) for key, times in grouped.items()}


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
