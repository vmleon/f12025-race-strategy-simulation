# Per-Sector Granularity Over Per-Lap

The simulation operates at per-sector granularity rather than per-lap. This is 3x more data but captures effects that are sector-specific:

- Overtakes happen in specific sectors (DRS zones, heavy braking points)
- A position change in sector 1 changes dirty air and DRS dynamics for sectors 2 and 3
- DRS zones exist in specific sectors, so sector-level data models which sectors allow overtaking

This produces ~60 rows per lap (3 sectors × 20 cars) — still very manageable.

## See Also

- `design/MONTECARLO.md` — simulation algorithm that steps sector-by-sector
- `design/DATABASE_DESIGN.md` — `sector_snapshots` table built around this granularity
