package dev.victormartin.telemetry.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Calibrated per-sector pace baselines from {@code sector_pace_baselines},
 * fitted by calibration.pipeline._fit_sector_baselines. Widen-by-ORDER-BY:
 * closest fuel bucket, then matching weather, then closest temp. Replaces
 * PaceBaselineLookup.
 */
@Component
public class SectorBaselineLookup {

    private static final Logger log = LoggerFactory.getLogger(SectorBaselineLookup.class);

    /** mean and perfect (min) sector times, index 0/1/2; 0 where no baseline. */
    public record SectorBaselines(List<Long> mean, List<Long> perfect) {}

    private final JdbcTemplate jdbc;

    public SectorBaselineLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SectorBaselines lookup(int trackId, int compound, boolean aiControlled,
                                  double fuelKg, int weather, int trackTempC) {
        List<Long> mean = new ArrayList<>(List.of(0L, 0L, 0L));
        List<Long> perfect = new ArrayList<>(List.of(0L, 0L, 0L));
        if (jdbc == null) return new SectorBaselines(mean, perfect);
        int fuelBucket = (int) Math.round(fuelKg / 20.0) * 20;
        int tempBucket = (int) Math.round(trackTempC / 10.0) * 10;
        String regime = aiControlled ? "AI" : "PLAYER";
        for (int sector = 0; sector < 3; sector++) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT mean_sector_ms, perfect_sector_ms FROM sector_pace_baselines "
                                + "WHERE track_id = ? AND sector_number = ? AND compound = ? "
                                + "  AND regime = ? "
                                + "ORDER BY ABS(fuel_bucket_kg - ?) ASC, "
                                + "         CASE WHEN weather = ? THEN 0 ELSE 1 END, "
                                + "         ABS(track_temp_bucket_c - ?) ASC "
                                + "FETCH FIRST 1 ROW ONLY",
                        trackId, sector, compound, regime, fuelBucket, weather, tempBucket);
                if (!rows.isEmpty()) {
                    Map<String, Object> r = rows.get(0);
                    mean.set(sector, ((Number) r.get("MEAN_SECTOR_MS")).longValue());
                    Object p = r.get("PERFECT_SECTOR_MS");
                    perfect.set(sector, p == null ? 0L : ((Number) p).longValue());
                }
            } catch (Exception e) {
                log.debug("SectorBaselineLookup failed track={} s={} compound={}: {}",
                        trackId, sector, compound, e.getMessage());
            }
        }
        return new SectorBaselines(mean, perfect);
    }
}
