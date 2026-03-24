package udp.server;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Populates the calibration_coefficients table with initial default values
 * for all knobs when a track has no existing coefficients. Both PLAYER and AI
 * regimes get identical defaults; they diverge as real data is calibrated.
 */
public class ColdStartDefaults {

    private static final String METHOD_NAME = "cold_start_default";

    record KnobDefault(String knobName, double value) {}

    static final List<KnobDefault> KNOB_DEFAULTS = List.of(
            new KnobDefault("tyre_deg_soft",       0.05),
            new KnobDefault("tyre_deg_medium",     0.03),
            new KnobDefault("tyre_deg_hard",       0.02),
            new KnobDefault("fuel_effect",         0.01),
            new KnobDefault("front_wing_damage",   0.02),
            new KnobDefault("floor_damage",        0.04),
            new KnobDefault("engine_damage",       0.01),
            new KnobDefault("dirty_air",           0.30),
            new KnobDefault("drs_advantage",      -0.20),
            new KnobDefault("overtake_probability", 0.15),
            new KnobDefault("safety_car_rate",     0.01)
    );

    private static final List<String> REGIMES = List.of("PLAYER", "AI");

    private final DbWriter dbWriter;
    private final DbReader dbReader;

    public ColdStartDefaults(DbWriter dbWriter, DbReader dbReader) {
        this.dbWriter = dbWriter;
        this.dbReader = dbReader;
    }

    /**
     * Inserts default coefficients for the given track if none exist yet.
     * Returns the number of rows inserted (0 if defaults already present).
     */
    public int ensureDefaults(Connection conn, int trackId) throws SQLException {
        if (dbReader.hasDefaultCoefficients(conn, trackId)) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        int inserted = 0;
        for (String regime : REGIMES) {
            for (KnobDefault knob : KNOB_DEFAULTS) {
                var coefficient = new DbWriter.CalibrationCoefficient(
                        trackId, knob.knobName, regime,
                        null,           // sector_number: null = track-wide
                        METHOD_NAME, knob.value,
                        null,           // confidence: unknown for defaults
                        null,           // score: no evaluation metric
                        1,              // is_default = true
                        0,              // session_count
                        0,              // data_point_count
                        null,           // game_settings_hash
                        now);
                dbWriter.insertCalibrationCoefficient(conn, coefficient);
                inserted++;
            }
        }
        return inserted;
    }
}
