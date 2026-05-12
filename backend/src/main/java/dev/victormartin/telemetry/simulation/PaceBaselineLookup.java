package dev.victormartin.telemetry.simulation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Look up a calibrated pace baseline for a car at a given track/conditions.
 *
 * The baselines are fitted by {@code calibration.pipeline._fit_pace_baselines}
 * from the raw rows in {@code lap_pace_observations}. They live in
 * {@code lap_pace_baselines}, keyed by
 * {@code (track_id, compound, regime, fuel_bucket_kg, weather, track_temp_bucket_c)}.
 *
 * The lookup widens by ORDER BY rather than retrying queries: an exact match
 * on all dimensions wins; otherwise we pick the row with the closest fuel
 * bucket, then prefer matching weather, then closest temp. This keeps the
 * lookup to a single SQL round-trip per car and makes a baseline available
 * even when the snapshot's conditions don't exactly match a fitted bucket.
 */
@Component
public class PaceBaselineLookup {

    private static final Logger log = LoggerFactory.getLogger(PaceBaselineLookup.class);

    private final JdbcTemplate jdbc;

    public PaceBaselineLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the closest calibrated lap time (ms) for the given conditions,
     * or 0 if no baseline exists for the track/compound/regime combination at
     * all. Returns 0 on any DB error — callers should fall back to the
     * simulator's circuit-default behaviour.
     */
    public long lookup(int trackId, int compound, boolean aiControlled,
                       double fuelKg, int weather, int trackTempC) {
        if (jdbc == null) return 0L;
        int fuelBucket = (int) Math.round(fuelKg / 20.0) * 20;
        int tempBucket = (int) Math.round(trackTempC / 10.0) * 10;
        String regime = aiControlled ? "AI" : "PLAYER";
        try {
            List<Long> result = jdbc.queryForList(
                    "SELECT mean_lap_ms FROM lap_pace_baselines "
                            + "WHERE track_id = ? AND compound = ? AND regime = ? "
                            + "ORDER BY ABS(fuel_bucket_kg - ?) ASC, "
                            + "         CASE WHEN weather = ? THEN 0 ELSE 1 END, "
                            + "         ABS(track_temp_bucket_c - ?) ASC "
                            + "FETCH FIRST 1 ROW ONLY",
                    Long.class,
                    trackId, compound, regime, fuelBucket, weather, tempBucket);
            return result.isEmpty() ? 0L : result.get(0);
        } catch (Exception e) {
            log.debug("PaceBaselineLookup: failed for track={} compound={} regime={}: {}",
                    trackId, compound, regime, e.getMessage());
            return 0L;
        }
    }
}
