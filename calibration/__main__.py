"""CLI entry point: python -m calibration run <trackId>"""

from __future__ import annotations

import sys

from calibration import db
from calibration.pipeline import run


def main() -> None:
    if len(sys.argv) < 3 or sys.argv[1] != "run":
        print("Usage: python -m calibration run <trackId>", file=sys.stderr)
        sys.exit(1)

    track_id = int(sys.argv[2])
    pool = db.get_pool()

    try:
        with pool.acquire() as conn:
            conn.autocommit = False
            run(conn, track_id)
            conn.commit()
    finally:
        db.close_pool()

    print(f"Calibration complete for track {track_id}")


if __name__ == "__main__":
    main()
