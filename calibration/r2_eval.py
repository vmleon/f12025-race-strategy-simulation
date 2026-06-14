"""Offline R² report for the dual-calibration evaluation (thesis §6.2 / §4.2.4).

Refits the per-regime sector-time model knobs — tyre degradation, fuel effect and
tyre wear rate — from `sector_snapshots`, reusing the *exact* calibration fitting
(`calibration.fitting.linear_regression` and the same grouping/thresholds as
`calibration.pipeline`). It reports the coefficient of determination (R²) per knob,
per sector and per regime (PLAYER vs AI), plus a sample-weighted mean R² of the
time model per regime — the headline number that supports the dual-calibration
decision (§4.2.4): a higher R² for the more deterministic AI regime than for the
human PLAYER regime.

This is the same R² the calibration pipeline already stores in
`calibration_coefficients` (column `r_squared`, changelog 014). Recomputing here
keeps the report self-contained and reproducible regardless of whether a
calibration run has happened — it is *not* a second calibration and writes nothing
to the database (read-only).

Outputs (under --out, default `calibration/r2_eval_out/`):
  - r2_by_knob_<track>.csv     one row per (regime, knob, sector): slope, R², n
  - r2_summary_<track>.csv     per-regime sample-weighted mean R² of the time model
  - deg_scatter_<track>.png    tyre age vs sector time, PLAYER|AI, fitted line + R²   [needs matplotlib]
  - r2_bars_<track>.png        mean R² per knob, grouped by regime                    [needs matplotlib]

Usage:
  calibration/venv/bin/python -m calibration.r2_eval <trackId> [--out DIR]

Requires the Oracle container up and populated (reads `calibration/config.properties`,
or ORACLE_HOST/ORACLE_PORT). matplotlib is optional: without it, the CSVs are still
written and the numbers printed; install with `calibration/venv/bin/pip install matplotlib`.
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import defaultdict
from dataclasses import dataclass

import numpy as np

from calibration import db
from calibration.fitting import linear_regression
from calibration.pipeline import (
    COMPOUND_KNOB_NAMES,
    MIN_FUEL_SAMPLES,
    MIN_TYRE_DEG_SAMPLES,
    MIN_WEAR_SAMPLES,
    WEAR_RATE_KNOB_NAMES,
    _most_worn,
)

REGIMES = ("PLAYER", "AI")
# Knobs that make up the additive sector-time model (the fit §4.2.4 talks about).
TIME_MODEL_PREFIXES = ("tyre_deg_", "fuel_effect")


@dataclass(frozen=True)
class Fit:
    regime: str
    knob: str
    sector: int | None  # None for sector-wide knobs (wear rate)
    slope: float
    r_squared: float
    n: int


def _fit_regime(regime: str, data: list[tuple]) -> list[Fit]:
    """Reproduce the pipeline's per-knob regressions for one regime."""
    fits: list[Fit] = []

    # Tyre degradation: sector_time vs tyre_age, per (sector, compound).
    deg_groups: dict[tuple[int, str], list[tuple]] = defaultdict(list)
    for r in data:
        knob = COMPOUND_KNOB_NAMES.get(r[db._COL_TYRE_COMPOUND])
        if knob is not None:
            deg_groups[(r[db._COL_SECTOR_NUMBER], knob)].append(r)
    for (sector, knob), group in deg_groups.items():
        if len(group) < MIN_TYRE_DEG_SAMPLES:
            continue
        x = np.array([r[db._COL_TYRE_AGE] for r in group], dtype=float)
        y = np.array([r[db._COL_SECTOR_TIME_MS] for r in group], dtype=float)
        reg = linear_regression(x, y)
        fits.append(Fit(regime, knob, sector, reg.slope, reg.r_squared, reg.n))

    # Fuel effect: sector_time vs fuel mass, per sector.
    fuel_by_sector: dict[int, list[tuple]] = defaultdict(list)
    for r in data:
        fuel = r[db._COL_FUEL]
        if fuel is not None and fuel > 0:
            fuel_by_sector[r[db._COL_SECTOR_NUMBER]].append(r)
    for sector, group in fuel_by_sector.items():
        if len(group) < MIN_FUEL_SAMPLES:
            continue
        x = np.array([r[db._COL_FUEL] for r in group], dtype=float)
        y = np.array([r[db._COL_SECTOR_TIME_MS] for r in group], dtype=float)
        reg = linear_regression(x, y)
        fits.append(Fit(regime, "fuel_effect", sector, reg.slope, reg.r_squared, reg.n))

    # Tyre wear rate: most-worn wheel wear % vs tyre_age, per compound (sector-wide).
    wear_groups: dict[str, list[tuple]] = defaultdict(list)
    for r in data:
        knob = WEAR_RATE_KNOB_NAMES.get(r[db._COL_TYRE_COMPOUND])
        if knob is not None:
            wear_groups[knob].append(r)
    for knob, group in wear_groups.items():
        pts = [(r[db._COL_TYRE_AGE], _most_worn(r)) for r in group]
        pts = [(age, wear) for age, wear in pts if wear is not None]
        if len(pts) < MIN_WEAR_SAMPLES:
            continue
        x = np.array([age for age, _ in pts], dtype=float)
        y = np.array([wear for _, wear in pts], dtype=float)
        reg = linear_regression(x, y)
        fits.append(Fit(regime, knob, None, reg.slope, reg.r_squared, reg.n))

    return fits


def _weighted_mean_r2(fits: list[Fit], prefixes: tuple[str, ...]) -> tuple[float, int]:
    """Sample-weighted mean R² over the fits whose knob matches a prefix."""
    sel = [f for f in fits if any(f.knob.startswith(p) for p in prefixes)]
    total_n = sum(f.n for f in sel)
    if total_n == 0:
        return (float("nan"), 0)
    return (sum(f.r_squared * f.n for f in sel) / total_n, total_n)


def _write_by_knob_csv(path: str, fits: list[Fit]) -> None:
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["regime", "knob", "sector", "slope", "r_squared", "n"])
        for f in sorted(fits, key=lambda f: (f.regime, f.knob, -1 if f.sector is None else f.sector)):
            w.writerow([
                f.regime, f.knob, "" if f.sector is None else f.sector,
                f"{f.slope:.4f}", f"{f.r_squared:.4f}", f.n,
            ])


def _write_summary_csv(path: str, summary: dict[str, tuple[float, int]]) -> None:
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["regime", "time_model_weighted_mean_r2", "n"])
        for regime in REGIMES:
            r2, n = summary.get(regime, (float("nan"), 0))
            w.writerow([regime, f"{r2:.4f}", n])


def _render_charts(out: str, track_id: int, fits_by_regime: dict[str, list[Fit]],
                   data_by_regime: dict[str, list[tuple]]) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib not installed — skipping charts "
              "(calibration/venv/bin/pip install matplotlib)")
        return

    # ── Chart 1: mean R² per knob, grouped by regime ────────────────────
    knobs: list[str] = []
    for regime in REGIMES:
        for f in fits_by_regime.get(regime, []):
            if f.knob not in knobs:
                knobs.append(f.knob)
    knobs.sort()
    if knobs:
        def mean_r2(regime: str, knob: str) -> float:
            sel = [f for f in fits_by_regime.get(regime, []) if f.knob == knob]
            n = sum(f.n for f in sel)
            return sum(f.r_squared * f.n for f in sel) / n if n else 0.0

        x = np.arange(len(knobs))
        width = 0.38
        fig, ax = plt.subplots(figsize=(max(7, len(knobs) * 1.1), 4.5))
        ax.bar(x - width / 2, [mean_r2("PLAYER", k) for k in knobs], width, label="Jugador")
        ax.bar(x + width / 2, [mean_r2("AI", k) for k in knobs], width, label="IA")
        ax.set_ylabel("R² (medio ponderado por nº de muestras)")
        ax.set_ylim(0, 1)
        ax.set_title(f"Ajuste de calibración por knob y régimen — circuito {track_id}")
        ax.set_xticks(x)
        ax.set_xticklabels(knobs, rotation=30, ha="right")
        ax.legend()
        fig.tight_layout()
        bars_path = os.path.join(out, f"r2_bars_{track_id}.png")
        fig.savefig(bars_path, dpi=150)
        plt.close(fig)
        print(f"Wrote {bars_path}")

    # ── Chart 2: tyre-deg scatter for the richest (compound, sector) ────
    # Pick the (compound, sector) with the most combined samples, plot PLAYER vs AI.
    best: tuple[str, int] | None = None
    best_n = -1
    counts: dict[tuple[str, int], int] = defaultdict(int)
    for regime in REGIMES:
        for f in fits_by_regime.get(regime, []):
            if f.knob.startswith("tyre_deg_") and f.sector is not None:
                counts[(f.knob, f.sector)] += f.n
    for key, n in counts.items():
        if n > best_n:
            best, best_n = key, n
    if best is not None:
        knob, sector = best
        compound = {v: k for k, v in COMPOUND_KNOB_NAMES.items()}[knob]
        fig, ax = plt.subplots(figsize=(7, 4.5))
        for regime, colour in (("PLAYER", "#1f77b4"), ("AI", "#d62728")):
            rows = [
                r for r in data_by_regime.get(regime, [])
                if r[db._COL_TYRE_COMPOUND] == compound
                and r[db._COL_SECTOR_NUMBER] == sector
            ]
            if not rows:
                continue
            xs = np.array([r[db._COL_TYRE_AGE] for r in rows], dtype=float)
            ys = np.array([r[db._COL_SECTOR_TIME_MS] for r in rows], dtype=float)
            reg = linear_regression(xs, ys)
            label = ("Jugador" if regime == "PLAYER" else "IA")
            ax.scatter(xs, ys, s=12, alpha=0.5, color=colour,
                       label=f"{label} (R²={reg.r_squared:.2f}, n={reg.n})")
            xline = np.array([xs.min(), xs.max()])
            ax.plot(xline, reg.slope * xline + reg.intercept, color=colour, lw=2)
        ax.set_xlabel("Edad del neumático (vueltas)")
        ax.set_ylabel("Tiempo por sector (ms)")
        ax.set_title(f"Degradación — {knob}, sector {sector}, circuito {track_id}")
        ax.legend()
        fig.tight_layout()
        scatter_path = os.path.join(out, f"deg_scatter_{track_id}.png")
        fig.savefig(scatter_path, dpi=150)
        plt.close(fig)
        print(f"Wrote {scatter_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Offline R² report for dual calibration.")
    parser.add_argument("track_id", type=int, help="track_id to evaluate")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "r2_eval_out"),
                        help="output directory (default: calibration/r2_eval_out/)")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    pool = db.get_pool()
    fits_by_regime: dict[str, list[Fit]] = {}
    data_by_regime: dict[str, list[tuple]] = {}
    all_fits: list[Fit] = []
    summary: dict[str, tuple[float, int]] = {}

    try:
        with pool.acquire() as conn:
            for regime in REGIMES:
                data = db.get_calibration_data(conn, args.track_id, regime)
                data_by_regime[regime] = data
                fits = _fit_regime(regime, data)
                fits_by_regime[regime] = fits
                all_fits.extend(fits)
                summary[regime] = _weighted_mean_r2(fits, TIME_MODEL_PREFIXES)
                print(f"{regime}: {len(data)} sectors, {len(fits)} fitted knobs, "
                      f"time-model R²={summary[regime][0]:.3f} (n={summary[regime][1]})")
    finally:
        db.close_pool()

    if not all_fits:
        print("No fits produced — is there captured data for this track? "
              "(run a Free Practice + race, then re-run.)", file=sys.stderr)
        sys.exit(1)

    by_knob = os.path.join(args.out, f"r2_by_knob_{args.track_id}.csv")
    summ = os.path.join(args.out, f"r2_summary_{args.track_id}.csv")
    _write_by_knob_csv(by_knob, all_fits)
    _write_summary_csv(summ, summary)
    print(f"Wrote {by_knob}\nWrote {summ}")

    p_r2 = summary.get("PLAYER", (float('nan'), 0))[0]
    ai_r2 = summary.get("AI", (float('nan'), 0))[0]
    print(f"\nTime-model R²  PLAYER={p_r2:.3f}  AI={ai_r2:.3f}  "
          f"(dual-calibration expects AI > PLAYER)")

    _render_charts(args.out, args.track_id, fits_by_regime, data_by_regime)


if __name__ == "__main__":
    main()
