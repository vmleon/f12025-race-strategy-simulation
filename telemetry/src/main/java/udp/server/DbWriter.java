package udp.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Insert methods for all telemetry tables. Uses raw JDBC with batch inserts
 * where appropriate. Each method takes a Connection so callers control
 * transaction scope.
 */
public class DbWriter {

    // ── sessions ────────────────────────────────────────────────────────

    private static final String INSERT_SESSION = """
            MERGE INTO sessions s USING (SELECT ? session_uid FROM dual) v
            ON (s.session_uid = v.session_uid)
            WHEN NOT MATCHED THEN INSERT (
                session_uid, track_id, track_length_m, session_type, total_laps,
                formula, sector2_start_dist, sector3_start_dist, ai_difficulty,
                safety_car_setting, car_damage_setting, car_damage_rate,
                low_fuel_mode
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    public record Session(
            long sessionUid, int trackId, double trackLengthM, int sessionType,
            int totalLaps, int formula, double sector2StartDist, double sector3StartDist,
            int aiDifficulty, int safetyCarSetting, int carDamageSetting,
            int carDamageRate, int lowFuelMode) {}

    public void insertSession(Connection conn, Session s) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SESSION)) {
            ps.setLong(1, s.sessionUid);
            ps.setLong(2, s.sessionUid);
            ps.setInt(3, s.trackId);
            ps.setDouble(4, s.trackLengthM);
            ps.setInt(5, s.sessionType);
            ps.setInt(6, s.totalLaps);
            ps.setInt(7, s.formula);
            ps.setDouble(8, s.sector2StartDist);
            ps.setDouble(9, s.sector3StartDist);
            ps.setInt(10, s.aiDifficulty);
            ps.setInt(11, s.safetyCarSetting);
            ps.setInt(12, s.carDamageSetting);
            ps.setInt(13, s.carDamageRate);
            ps.setInt(14, s.lowFuelMode);
            ps.executeUpdate();
        }
    }

    // ── participants ────────────────────────────────────────────────────

    private static final String INSERT_PARTICIPANT = """
            MERGE INTO participants p USING (SELECT ? session_uid, ? car_index FROM dual) v
            ON (p.session_uid = v.session_uid AND p.car_index = v.car_index)
            WHEN NOT MATCHED THEN INSERT (
                session_uid, car_index, driver_name, team_id, race_number,
                nationality, ai_controlled
            ) VALUES (?,?,?,?,?,?,?)
            """;

    public record Participant(
            long sessionUid, int carIndex, String driverName, int teamId,
            int raceNumber, int nationality, int aiControlled) {}

    public void insertParticipants(Connection conn, List<Participant> participants) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_PARTICIPANT)) {
            for (Participant p : participants) {
                ps.setLong(1, p.sessionUid);
                ps.setInt(2, p.carIndex);
                ps.setLong(3, p.sessionUid);
                ps.setInt(4, p.carIndex);
                ps.setString(5, p.driverName);
                ps.setInt(6, p.teamId);
                ps.setInt(7, p.raceNumber);
                ps.setInt(8, p.nationality);
                ps.setInt(9, p.aiControlled);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── sector_snapshots ────────────────────────────────────────────────

    private static final String INSERT_SECTOR_SNAPSHOT = """
            MERGE INTO sector_snapshots ss
            USING (SELECT ? session_uid, ? car_index, ? lap_number, ? sector_number FROM dual) v
            ON (ss.session_uid = v.session_uid AND ss.car_index = v.car_index
                AND ss.lap_number = v.lap_number AND ss.sector_number = v.sector_number)
            WHEN NOT MATCHED THEN INSERT (
                session_uid, car_index, lap_number, sector_number,
                sector_time_ms, lap_time_ms, car_position,
                gap_to_car_ahead_ms, gap_to_leader_ms,
                pit_status, num_pit_stops, penalties_sec,
                lap_invalid, corner_cutting_warnings, driver_status,
                speed_trap_kmh,
                tyre_compound_actual, tyre_compound_visual, tyre_age_laps,
                tyre_wear_rl, tyre_wear_rr, tyre_wear_fl, tyre_wear_fr,
                tyre_damage_rl, tyre_damage_rr, tyre_damage_fl, tyre_damage_fr,
                tyre_blisters_rl, tyre_blisters_rr, tyre_blisters_fl, tyre_blisters_fr,
                front_wing_damage_l, front_wing_damage_r, rear_wing_damage,
                floor_damage, diffuser_damage, sidepod_damage,
                engine_damage, gearbox_damage,
                tyre_surface_temp_rl, tyre_surface_temp_rr,
                tyre_surface_temp_fl, tyre_surface_temp_fr,
                tyre_inner_temp_rl, tyre_inner_temp_rr,
                tyre_inner_temp_fl, tyre_inner_temp_fr,
                brake_temp_rl, brake_temp_rr, brake_temp_fl, brake_temp_fr,
                engine_temp,
                fuel_in_tank_kg, fuel_remaining_laps, ers_deploy_mode,
                drs_allowed, drs_activation_dist,
                weather, track_temp, air_temp, safety_car_status,
                session_type,
                recovered, frame_identifier
            ) VALUES (
                ?,?,?,?,
                ?,?,?,
                ?,?,
                ?,?,?,
                ?,?,?,
                ?,
                ?,?,?,
                ?,?,?,?,
                ?,?,?,?,
                ?,?,?,?,
                ?,?,?,
                ?,?,?,
                ?,?,
                ?,?,?,?,
                ?,?,?,?,
                ?,?,?,?,
                ?,
                ?,?,?,
                ?,?,
                ?,?,?,?,
                ?,
                ?,?
            )
            """;

    public record SectorSnapshot(
            long sessionUid, int carIndex, int lapNumber, int sectorNumber,
            long sectorTimeMs, long lapTimeMs, int carPosition,
            long gapToCarAheadMs, long gapToLeaderMs,
            int pitStatus, int numPitStops, int penaltiesSec,
            int lapInvalid, int cornerCuttingWarnings, int driverStatus,
            double speedTrapKmh,
            int tyreCompoundActual, int tyreCompoundVisual, int tyreAgeLaps,
            double tyreWearRl, double tyreWearRr, double tyreWearFl, double tyreWearFr,
            int tyreDamageRl, int tyreDamageRr, int tyreDamageFl, int tyreDamageFr,
            int tyreBlistersRl, int tyreBlistersRr, int tyreBlistersFlr, int tyreBlistersFlFr,
            int frontWingDamageL, int frontWingDamageR, int rearWingDamage,
            int floorDamage, int diffuserDamage, int sidepodDamage,
            int engineDamage, int gearboxDamage,
            int tyreSurfaceTempRl, int tyreSurfaceTempRr,
            int tyreSurfaceTempFl, int tyreSurfaceTempFr,
            int tyreInnerTempRl, int tyreInnerTempRr,
            int tyreInnerTempFl, int tyreInnerTempFr,
            int brakeTempRl, int brakeTempRr, int brakeTempFl, int brakeTempFr,
            int engineTemp,
            double fuelInTankKg, double fuelRemainingLaps, int ersDeployMode,
            int drsAllowed, double drsActivationDist,
            int weather, int trackTemp, int airTemp, int safetyCarStatus,
            int sessionType,
            int recovered, long frameIdentifier) {}

    public void insertSectorSnapshots(Connection conn, List<SectorSnapshot> snapshots) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SECTOR_SNAPSHOT)) {
            for (SectorSnapshot s : snapshots) {
                // MERGE ON keys
                ps.setLong(1, s.sessionUid);
                ps.setInt(2, s.carIndex);
                ps.setInt(3, s.lapNumber);
                ps.setInt(4, s.sectorNumber);
                // INSERT values
                int i = 5;
                ps.setLong(i++, s.sessionUid);
                ps.setInt(i++, s.carIndex);
                ps.setInt(i++, s.lapNumber);
                ps.setInt(i++, s.sectorNumber);
                ps.setLong(i++, s.sectorTimeMs);
                ps.setLong(i++, s.lapTimeMs);
                ps.setInt(i++, s.carPosition);
                ps.setLong(i++, s.gapToCarAheadMs);
                ps.setLong(i++, s.gapToLeaderMs);
                ps.setInt(i++, s.pitStatus);
                ps.setInt(i++, s.numPitStops);
                ps.setInt(i++, s.penaltiesSec);
                ps.setInt(i++, s.lapInvalid);
                ps.setInt(i++, s.cornerCuttingWarnings);
                ps.setInt(i++, s.driverStatus);
                ps.setDouble(i++, s.speedTrapKmh);
                ps.setInt(i++, s.tyreCompoundActual);
                ps.setInt(i++, s.tyreCompoundVisual);
                ps.setInt(i++, s.tyreAgeLaps);
                ps.setDouble(i++, s.tyreWearRl);
                ps.setDouble(i++, s.tyreWearRr);
                ps.setDouble(i++, s.tyreWearFl);
                ps.setDouble(i++, s.tyreWearFr);
                ps.setInt(i++, s.tyreDamageRl);
                ps.setInt(i++, s.tyreDamageRr);
                ps.setInt(i++, s.tyreDamageFl);
                ps.setInt(i++, s.tyreDamageFr);
                ps.setInt(i++, s.tyreBlistersRl);
                ps.setInt(i++, s.tyreBlistersRr);
                ps.setInt(i++, s.tyreBlistersFlr);
                ps.setInt(i++, s.tyreBlistersFlFr);
                ps.setInt(i++, s.frontWingDamageL);
                ps.setInt(i++, s.frontWingDamageR);
                ps.setInt(i++, s.rearWingDamage);
                ps.setInt(i++, s.floorDamage);
                ps.setInt(i++, s.diffuserDamage);
                ps.setInt(i++, s.sidepodDamage);
                ps.setInt(i++, s.engineDamage);
                ps.setInt(i++, s.gearboxDamage);
                ps.setInt(i++, s.tyreSurfaceTempRl);
                ps.setInt(i++, s.tyreSurfaceTempRr);
                ps.setInt(i++, s.tyreSurfaceTempFl);
                ps.setInt(i++, s.tyreSurfaceTempFr);
                ps.setInt(i++, s.tyreInnerTempRl);
                ps.setInt(i++, s.tyreInnerTempRr);
                ps.setInt(i++, s.tyreInnerTempFl);
                ps.setInt(i++, s.tyreInnerTempFr);
                ps.setInt(i++, s.brakeTempRl);
                ps.setInt(i++, s.brakeTempRr);
                ps.setInt(i++, s.brakeTempFl);
                ps.setInt(i++, s.brakeTempFr);
                ps.setInt(i++, s.engineTemp);
                ps.setDouble(i++, s.fuelInTankKg);
                ps.setDouble(i++, s.fuelRemainingLaps);
                ps.setInt(i++, s.ersDeployMode);
                ps.setInt(i++, s.drsAllowed);
                ps.setDouble(i++, s.drsActivationDist);
                ps.setInt(i++, s.weather);
                ps.setInt(i++, s.trackTemp);
                ps.setInt(i++, s.airTemp);
                ps.setInt(i++, s.safetyCarStatus);
                ps.setInt(i++, s.sessionType);
                ps.setInt(i++, s.recovered);
                ps.setLong(i, s.frameIdentifier);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── session_events ──────────────────────────────────────────────────

    private static final String INSERT_EVENT = """
            INSERT INTO session_events (
                event_id, session_uid, frame_identifier, event_code,
                car_index, penalty_seconds, other_car_index, lap_number,
                flashback_frame_id, flashback_session_time
            ) VALUES (seq_session_events.NEXTVAL, ?,?,?,?,?,?,?,?,?)
            """;

    public record Event(
            long sessionUid, long frameIdentifier, String eventCode,
            Integer carIndex, Integer penaltySeconds,
            Integer otherCarIndex, Integer lapNumber,
            Long flashbackFrameId, Double flashbackSessionTime) {}

    public void insertEvent(Connection conn, Event e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_EVENT)) {
            ps.setLong(1, e.sessionUid);
            ps.setLong(2, e.frameIdentifier);
            ps.setString(3, e.eventCode);
            setNullableInt(ps, 4, e.carIndex);
            setNullableInt(ps, 5, e.penaltySeconds);
            setNullableInt(ps, 6, e.otherCarIndex);
            setNullableInt(ps, 7, e.lapNumber);
            setNullableLong(ps, 8, e.flashbackFrameId);
            setNullableDouble(ps, 9, e.flashbackSessionTime);
            ps.executeUpdate();
        }
    }

    // ── driving_events ──────────────────────────────────────────────────

    private static final String INSERT_DRIVING_EVENT = """
            INSERT INTO driving_events (
                event_id, session_uid, car_index, track_id, session_type,
                lap_number, sector_number, lap_distance_m, lap_distance_end_m,
                event_type, location_detail, peak_intensity, intensity_signal,
                duration_ms, entry_speed_kmh,
                brake_peak, throttle_peak, steer_abs_peak,
                brake_at_peak, throttle_at_peak, steer_at_peak,
                frame_identifier
            ) VALUES (seq_driving_events.NEXTVAL, ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    public record DrivingEventRow(
            long sessionUid, int carIndex, Integer trackId, int sessionType,
            int lapNumber, int sectorNumber, double lapDistanceM, Double lapDistanceEndM,
            String eventType, String locationDetail, double peakIntensity, String intensitySignal,
            long durationMs, double entrySpeedKmh,
            double brakePeak, double throttlePeak, double steerAbsPeak,
            double brakeAtPeak, double throttleAtPeak, double steerAtPeak,
            long frameIdentifier) {}

    public void insertDrivingEvents(Connection conn, List<DrivingEventRow> events) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_DRIVING_EVENT)) {
            for (DrivingEventRow e : events) {
                int i = 1;
                ps.setLong(i++, e.sessionUid());
                ps.setInt(i++, e.carIndex());
                setNullableInt(ps, i++, e.trackId());
                ps.setInt(i++, e.sessionType());
                ps.setInt(i++, e.lapNumber());
                ps.setInt(i++, e.sectorNumber());
                ps.setDouble(i++, e.lapDistanceM());
                setNullableDouble(ps, i++, e.lapDistanceEndM());
                ps.setString(i++, e.eventType());
                ps.setString(i++, e.locationDetail());
                ps.setDouble(i++, e.peakIntensity());
                ps.setString(i++, e.intensitySignal());
                ps.setLong(i++, e.durationMs());
                ps.setDouble(i++, e.entrySpeedKmh());
                ps.setDouble(i++, e.brakePeak());
                ps.setDouble(i++, e.throttlePeak());
                ps.setDouble(i++, e.steerAbsPeak());
                ps.setDouble(i++, e.brakeAtPeak());
                ps.setDouble(i++, e.throttleAtPeak());
                ps.setDouble(i++, e.steerAtPeak());
                ps.setLong(i, e.frameIdentifier());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── flashback cleanup ────────────────────────────────────────────────

    private static final String DELETE_SECTORS_AFTER_FRAME = """
            DELETE FROM sector_snapshots
            WHERE session_uid = ? AND frame_identifier > ?
            """;

    private static final String DELETE_EVENTS_AFTER_FRAME = """
            DELETE FROM session_events
            WHERE session_uid = ? AND frame_identifier > ?
            """;

    private static final String DELETE_DRIVING_EVENTS_AFTER_FRAME = """
            DELETE FROM driving_events
            WHERE session_uid = ? AND frame_identifier > ?
            """;

    /**
     * Delete sector_snapshots, session_events and driving_events recorded after the given
     * flashback frame, so replayed data can be re-inserted cleanly.
     * Returns the total number of deleted rows.
     */
    public int deleteFlashbackData(Connection conn, long sessionUid, long flashbackFrameId) throws SQLException {
        int deleted = 0;
        try (PreparedStatement ps = conn.prepareStatement(DELETE_SECTORS_AFTER_FRAME)) {
            ps.setLong(1, sessionUid);
            ps.setLong(2, flashbackFrameId);
            deleted += ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(DELETE_EVENTS_AFTER_FRAME)) {
            ps.setLong(1, sessionUid);
            ps.setLong(2, flashbackFrameId);
            deleted += ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(DELETE_DRIVING_EVENTS_AFTER_FRAME)) {
            ps.setLong(1, sessionUid);
            ps.setLong(2, flashbackFrameId);
            deleted += ps.executeUpdate();
        }
        return deleted;
    }

    // ── tyre_sets ───────────────────────────────────────────────────────

    private static final String INSERT_TYRE_SET = """
            MERGE INTO tyre_sets t
            USING (SELECT ? session_uid, ? car_index, ? set_index FROM dual) v
            ON (t.session_uid = v.session_uid AND t.car_index = v.car_index AND t.set_index = v.set_index)
            WHEN NOT MATCHED THEN INSERT (
                session_uid, car_index, set_index,
                tyre_compound_actual, tyre_compound_visual,
                wear, available, life_span, usable_life,
                lap_delta_time_ms, fitted
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            WHEN MATCHED THEN UPDATE SET
                wear = ?, available = ?, life_span = ?, usable_life = ?,
                lap_delta_time_ms = ?, fitted = ?
            """;

    public record TyreSet(
            long sessionUid, int carIndex, int setIndex,
            int tyreCompoundActual, int tyreCompoundVisual,
            double wear, int available, int lifeSpan, int usableLife,
            long lapDeltaTimeMs, int fitted) {}

    public void insertTyreSets(Connection conn, List<TyreSet> tyreSets) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_TYRE_SET)) {
            for (TyreSet t : tyreSets) {
                // MERGE ON keys
                ps.setLong(1, t.sessionUid);
                ps.setInt(2, t.carIndex);
                ps.setInt(3, t.setIndex);
                // INSERT values
                ps.setLong(4, t.sessionUid);
                ps.setInt(5, t.carIndex);
                ps.setInt(6, t.setIndex);
                ps.setInt(7, t.tyreCompoundActual);
                ps.setInt(8, t.tyreCompoundVisual);
                ps.setDouble(9, t.wear);
                ps.setInt(10, t.available);
                ps.setInt(11, t.lifeSpan);
                ps.setInt(12, t.usableLife);
                ps.setLong(13, t.lapDeltaTimeMs);
                ps.setInt(14, t.fitted);
                // UPDATE values
                ps.setDouble(15, t.wear);
                ps.setInt(16, t.available);
                ps.setInt(17, t.lifeSpan);
                ps.setInt(18, t.usableLife);
                ps.setLong(19, t.lapDeltaTimeMs);
                ps.setInt(20, t.fitted);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── final_classifications ───────────────────────────────────────────

    private static final String INSERT_FINAL_CLASSIFICATION = """
            MERGE INTO final_classifications fc
            USING (SELECT ? session_uid, ? car_index FROM dual) v
            ON (fc.session_uid = v.session_uid AND fc.car_index = v.car_index)
            WHEN NOT MATCHED THEN INSERT (
                session_uid, car_index, position, grid_position, num_laps,
                points, num_pit_stops, result_status, result_reason,
                best_lap_time_ms, total_race_time_s, penalties_time_sec,
                num_penalties, num_tyre_stints,
                stint1_actual, stint1_visual, stint1_end_lap,
                stint2_actual, stint2_visual, stint2_end_lap,
                stint3_actual, stint3_visual, stint3_end_lap,
                stint4_actual, stint4_visual, stint4_end_lap,
                stint5_actual, stint5_visual, stint5_end_lap,
                stint6_actual, stint6_visual, stint6_end_lap,
                stint7_actual, stint7_visual, stint7_end_lap,
                stint8_actual, stint8_visual, stint8_end_lap
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    public record FinalClassification(
            long sessionUid, int carIndex, int position, int gridPosition,
            int numLaps, int points, int numPitStops, int resultStatus,
            int resultReason, long bestLapTimeMs, double totalRaceTimeS,
            int penaltiesTimeSec, int numPenalties, int numTyreStints,
            int[] stintActual, int[] stintVisual, int[] stintEndLap) {}

    public void insertFinalClassifications(Connection conn, List<FinalClassification> classifications) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_FINAL_CLASSIFICATION)) {
            for (FinalClassification fc : classifications) {
                // MERGE ON keys
                ps.setLong(1, fc.sessionUid);
                ps.setInt(2, fc.carIndex);
                // INSERT values
                int i = 3;
                ps.setLong(i++, fc.sessionUid);
                ps.setInt(i++, fc.carIndex);
                ps.setInt(i++, fc.position);
                ps.setInt(i++, fc.gridPosition);
                ps.setInt(i++, fc.numLaps);
                ps.setInt(i++, fc.points);
                ps.setInt(i++, fc.numPitStops);
                ps.setInt(i++, fc.resultStatus);
                ps.setInt(i++, fc.resultReason);
                ps.setLong(i++, fc.bestLapTimeMs);
                ps.setDouble(i++, fc.totalRaceTimeS);
                ps.setInt(i++, fc.penaltiesTimeSec);
                ps.setInt(i++, fc.numPenalties);
                ps.setInt(i++, fc.numTyreStints);
                // 8 stints × 3 columns
                for (int s = 0; s < 8; s++) {
                    ps.setInt(i++, s < fc.stintActual.length ? fc.stintActual[s] : 0);
                    ps.setInt(i++, s < fc.stintVisual.length ? fc.stintVisual[s] : 0);
                    ps.setInt(i++, s < fc.stintEndLap.length ? fc.stintEndLap[s] : 0);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) {
            ps.setLong(index, value);
        } else {
            ps.setNull(index, java.sql.Types.BIGINT);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value != null) {
            ps.setInt(index, value);
        } else {
            ps.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value != null) {
            ps.setDouble(index, value);
        } else {
            ps.setNull(index, java.sql.Types.DOUBLE);
        }
    }
}
