package dev.victormartin.telemetry.simulation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Loads calibration coefficients from the database into the in-memory
 * Coefficients store used by MonteCarloEngine.
 */
@Repository
public class CoefficientRepository {

    private final JdbcTemplate jdbc;

    public CoefficientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loads the most recent coefficient for each (knob, regime, sector) combination
     * for the given track. Returns a populated Coefficients instance.
     */
    public Coefficients loadForTrack(int trackId) {
        var coefficients = new Coefficients();

        jdbc.query("""
                SELECT knob_name, calibration_regime, sector_number, value
                FROM (
                    SELECT knob_name, calibration_regime, sector_number, value,
                           ROW_NUMBER() OVER (
                               PARTITION BY knob_name, calibration_regime, NVL(sector_number, -1)
                               ORDER BY trained_at DESC
                           ) rn
                    FROM calibration_coefficients
                    WHERE track_id = ?
                )
                WHERE rn = 1
                """,
                rs -> {
                    String knob = rs.getString("knob_name");
                    String regime = rs.getString("calibration_regime");
                    int sector = rs.getInt("sector_number");
                    if (rs.wasNull()) sector = -1;
                    double value = rs.getDouble("value");
                    coefficients.put(knob, regime, sector, value);
                },
                trackId);

        return coefficients;
    }
}
