package udp.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Query methods for calibration and simulation reads.
 */
public class DbReader {

    // ── sector snapshots ────────────────────────────────────────────────

    private static final String SELECT_SECTOR_SNAPSHOTS = """
            SELECT ss.*, p.ai_controlled
            FROM sector_snapshots ss
            JOIN participants p ON p.session_uid = ss.session_uid AND p.car_index = ss.car_index
            WHERE ss.session_uid = ? AND p.ai_controlled = ?
            ORDER BY ss.car_index, ss.lap_number, ss.sector_number
            """;

    public record SectorSnapshotRow(
            long sessionUid, int carIndex, int lapNumber, int sectorNumber,
            long sectorTimeMs, long lapTimeMs, int carPosition,
            long gapToCarAheadMs, long gapToLeaderMs,
            int pitStatus, int numPitStops, int penaltiesSec,
            int lapInvalid, int cornerCuttingWarnings, int driverStatus,
            double speedTrapKmh,
            int tyreCompoundActual, int tyreCompoundVisual, int tyreAgeLaps,
            double tyreWearRl, double tyreWearRr, double tyreWearFl, double tyreWearFr,
            int tyreSurfaceTempRl, int tyreSurfaceTempRr,
            int tyreSurfaceTempFl, int tyreSurfaceTempFr,
            int tyreInnerTempRl, int tyreInnerTempRr,
            int tyreInnerTempFl, int tyreInnerTempFr,
            int brakeTempRl, int brakeTempRr, int brakeTempFl, int brakeTempFr,
            int engineTemp,
            double fuelInTankKg, double fuelRemainingLaps, int ersDeployMode,
            int weather, int trackTemp, int airTemp,
            int recovered, int outlier, int aiControlled) {}

    public List<SectorSnapshotRow> getSectorSnapshots(Connection conn, long sessionUid, int aiControlled) throws SQLException {
        List<SectorSnapshotRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SECTOR_SNAPSHOTS)) {
            ps.setLong(1, sessionUid);
            ps.setInt(2, aiControlled);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SectorSnapshotRow(
                            rs.getLong("session_uid"), rs.getInt("car_index"),
                            rs.getInt("lap_number"), rs.getInt("sector_number"),
                            rs.getLong("sector_time_ms"), rs.getLong("lap_time_ms"),
                            rs.getInt("car_position"),
                            rs.getLong("gap_to_car_ahead_ms"), rs.getLong("gap_to_leader_ms"),
                            rs.getInt("pit_status"), rs.getInt("num_pit_stops"),
                            rs.getInt("penalties_sec"),
                            rs.getInt("lap_invalid"), rs.getInt("corner_cutting_warnings"),
                            rs.getInt("driver_status"),
                            rs.getDouble("speed_trap_kmh"),
                            rs.getInt("tyre_compound_actual"), rs.getInt("tyre_compound_visual"),
                            rs.getInt("tyre_age_laps"),
                            rs.getDouble("tyre_wear_rl"), rs.getDouble("tyre_wear_rr"),
                            rs.getDouble("tyre_wear_fl"), rs.getDouble("tyre_wear_fr"),
                            rs.getInt("tyre_surface_temp_rl"), rs.getInt("tyre_surface_temp_rr"),
                            rs.getInt("tyre_surface_temp_fl"), rs.getInt("tyre_surface_temp_fr"),
                            rs.getInt("tyre_inner_temp_rl"), rs.getInt("tyre_inner_temp_rr"),
                            rs.getInt("tyre_inner_temp_fl"), rs.getInt("tyre_inner_temp_fr"),
                            rs.getInt("brake_temp_rl"), rs.getInt("brake_temp_rr"),
                            rs.getInt("brake_temp_fl"), rs.getInt("brake_temp_fr"),
                            rs.getInt("engine_temp"),
                            rs.getDouble("fuel_in_tank_kg"), rs.getDouble("fuel_remaining_laps"),
                            rs.getInt("ers_deploy_mode"),
                            rs.getInt("weather"), rs.getInt("track_temp"), rs.getInt("air_temp"),
                            rs.getInt("recovered"), rs.getInt("outlier"),
                            rs.getInt("ai_controlled")));
                }
            }
        }
        return rows;
    }

    // ── sessions for track ──────────────────────────────────────────────

    private static final String SELECT_SESSIONS_FOR_TRACK = """
            SELECT session_uid, track_id, track_length_m, session_type, total_laps,
                   formula, ai_difficulty, created_at
            FROM sessions
            WHERE track_id = ?
            ORDER BY created_at DESC
            """;

    public record SessionRow(
            long sessionUid, int trackId, double trackLengthM, int sessionType,
            int totalLaps, int formula, int aiDifficulty,
            java.sql.Timestamp createdAt) {}

    public List<SessionRow> getSessionsForTrack(Connection conn, int trackId) throws SQLException {
        List<SessionRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SESSIONS_FOR_TRACK)) {
            ps.setInt(1, trackId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SessionRow(
                            rs.getLong("session_uid"), rs.getInt("track_id"),
                            rs.getDouble("track_length_m"), rs.getInt("session_type"),
                            rs.getInt("total_laps"), rs.getInt("formula"),
                            rs.getInt("ai_difficulty"),
                            rs.getTimestamp("created_at")));
                }
            }
        }
        return rows;
    }

    // ── calibration coefficients ────────────────────────────────────────

    private static final String SELECT_CALIBRATION_COEFFICIENTS = """
            SELECT coefficient_id, track_id, knob_name, calibration_regime,
                   sector_number, method_name, value, confidence, score,
                   is_default, session_count, data_point_count,
                   game_settings_hash, trained_at
            FROM calibration_coefficients
            WHERE track_id = ? AND knob_name = ? AND calibration_regime = ?
            ORDER BY trained_at DESC
            """;

    public record CalibrationCoefficientRow(
            long coefficientId, int trackId, String knobName, String calibrationRegime,
            Integer sectorNumber, String methodName, double value,
            Double confidence, Double score, int isDefault,
            int sessionCount, int dataPointCount,
            String gameSettingsHash, java.sql.Timestamp trainedAt) {}

    public List<CalibrationCoefficientRow> getCalibrationCoefficients(
            Connection conn, int trackId, String knobName, String regime) throws SQLException {
        List<CalibrationCoefficientRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_CALIBRATION_COEFFICIENTS)) {
            ps.setInt(1, trackId);
            ps.setString(2, knobName);
            ps.setString(3, regime);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sn = rs.getInt("sector_number");
                    Double conf = rs.getDouble("confidence");
                    if (rs.wasNull()) conf = null;
                    Double sc = rs.getDouble("score");
                    if (rs.wasNull()) sc = null;
                    rows.add(new CalibrationCoefficientRow(
                            rs.getLong("coefficient_id"), rs.getInt("track_id"),
                            rs.getString("knob_name"), rs.getString("calibration_regime"),
                            rs.wasNull() ? null : sn, rs.getString("method_name"),
                            rs.getDouble("value"), conf, sc,
                            rs.getInt("is_default"), rs.getInt("session_count"),
                            rs.getInt("data_point_count"),
                            rs.getString("game_settings_hash"),
                            rs.getTimestamp("trained_at")));
                }
            }
        }
        return rows;
    }

    // ── final classifications ───────────────────────────────────────────

    private static final String SELECT_FINAL_CLASSIFICATIONS = """
            SELECT session_uid, car_index, position, grid_position, num_laps,
                   points, num_pit_stops, result_status, result_reason,
                   best_lap_time_ms, total_race_time_s, penalties_time_sec,
                   num_penalties, num_tyre_stints
            FROM final_classifications
            WHERE session_uid = ?
            ORDER BY position
            """;

    public record FinalClassificationRow(
            long sessionUid, int carIndex, int position, int gridPosition,
            int numLaps, int points, int numPitStops, int resultStatus,
            int resultReason, long bestLapTimeMs, double totalRaceTimeS,
            int penaltiesTimeSec, int numPenalties, int numTyreStints) {}

    public List<FinalClassificationRow> getFinalClassifications(Connection conn, long sessionUid) throws SQLException {
        List<FinalClassificationRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_FINAL_CLASSIFICATIONS)) {
            ps.setLong(1, sessionUid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new FinalClassificationRow(
                            rs.getLong("session_uid"), rs.getInt("car_index"),
                            rs.getInt("position"), rs.getInt("grid_position"),
                            rs.getInt("num_laps"), rs.getInt("points"),
                            rs.getInt("num_pit_stops"), rs.getInt("result_status"),
                            rs.getInt("result_reason"), rs.getLong("best_lap_time_ms"),
                            rs.getDouble("total_race_time_s"), rs.getInt("penalties_time_sec"),
                            rs.getInt("num_penalties"), rs.getInt("num_tyre_stints")));
                }
            }
        }
        return rows;
    }
}
