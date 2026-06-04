package dev.victormartin.telemetry.simulation;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Recent per-sector times for a car, read from {@code sector_snapshots} — the
 * same table telemetry persists (game-validated S1/S2/S3) and calibration reads.
 * Stateless: no in-memory buffer, no rehydration. Replaces LapHistoryTracker.
 */
@Component
public class SectorHistoryLookup {

    private static final Logger log = LoggerFactory.getLogger(SectorHistoryLookup.class);
    private static final int WINDOW = 3;

    private final JdbcTemplate jdbc;

    public SectorHistoryLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Last WINDOW clean sector times (newest first) for one car/compound/sector. */
    public List<Long> recent(int trackId, int carIndex, int compound, int sector) {
        if (jdbc == null) return List.of();
        try {
            return jdbc.queryForList(
                    "SELECT sector_time_ms FROM ("
                            + " SELECT ss.sector_time_ms"
                            + " FROM sector_snapshots ss"
                            + " JOIN sessions s ON s.session_uid = ss.session_uid"
                            + " WHERE s.track_id = ? AND ss.car_index = ?"
                            + "   AND ss.tyre_compound_actual = ? AND ss.sector_number = ?"
                            + "   AND ss.outlier = 0 AND ss.lap_invalid = 0"
                            + "   AND ss.pit_status = 0 AND ss.sector_time_ms > 0"
                            + " ORDER BY ss.created_at DESC"
                            + ") WHERE ROWNUM <= ?",
                    Long.class, trackId, carIndex, compound, sector, WINDOW);
        } catch (Exception e) {
            log.debug("SectorHistoryLookup: failed track={} car={} compound={} s={}: {}",
                    trackId, carIndex, compound, sector, e.getMessage());
            return List.of();
        }
    }

    /** The three sectors' recent times: index 0/1/2. */
    public List<List<Long>> recentBySector(int trackId, int carIndex, int compound) {
        List<List<Long>> out = new ArrayList<>(3);
        for (int s = 0; s < 3; s++) out.add(recent(trackId, carIndex, compound, s));
        return out;
    }

    /** Approx laps recorded for a car at a track (sector-0 row count). Powers the
     * strategy player-pace gate that LapHistoryTracker.totalLapsRecorded served. */
    public int lapsRecorded(int trackId, int carIndex) {
        if (jdbc == null) return 0;
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sector_snapshots ss"
                            + " JOIN sessions s ON s.session_uid = ss.session_uid"
                            + " WHERE s.track_id = ? AND ss.car_index = ?"
                            + "   AND ss.sector_number = 0 AND ss.outlier = 0"
                            + "   AND ss.lap_invalid = 0 AND ss.pit_status = 0",
                    Integer.class, trackId, carIndex);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.debug("SectorHistoryLookup.lapsRecorded failed: {}", e.getMessage());
            return 0;
        }
    }
}
