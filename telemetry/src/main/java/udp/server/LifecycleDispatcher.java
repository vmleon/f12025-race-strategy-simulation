package udp.server;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates database writes for session lifecycle data.
 * Tracks seen session UIDs to deduplicate one-off writes (sessions,
 * participants, final classifications). Events are written immediately
 * without deduplication. Tyre sets are re-snapshotted on pit stop detection.
 */
public class LifecycleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LifecycleDispatcher.class);

    private final ConnectionFactory connectionFactory;
    private final DbWriter dbWriter;

    private final Set<Long> seenSessions = new HashSet<>();
    private final Set<Long> seenParticipants = new HashSet<>();
    private final Set<Long> seenFinalClassifications = new HashSet<>();

    // Previous pit status per car for pit stop detection (index = carIndex)
    private final int[] prevPitStatus = new int[22];

    public LifecycleDispatcher(ConnectionFactory connectionFactory, DbWriter dbWriter) {
        this.connectionFactory = connectionFactory;
        this.dbWriter = dbWriter;
    }

    /**
     * Write session metadata on first Session packet per sessionUid.
     */
    public void onSession(long sessionUid, SessionData session) {
        if (!seenSessions.add(sessionUid)) {
            return;
        }
        DbWriter.Session record = new DbWriter.Session(
                sessionUid,
                session.trackId,
                session.trackLength,
                session.sessionType,
                session.totalLaps,
                session.formula,
                session.sector2LapDistanceStart,
                session.sector3LapDistanceStart,
                session.aiDifficulty,
                session.safetyCar,
                session.carDamage,
                session.carDamageRate,
                session.lowFuelMode
        );
        try (Connection conn = connectionFactory.getConnection()) {
            dbWriter.insertSession(conn, record);
            conn.commit();
            log.info("DB session uid={} track={}", sessionUid, session.trackId);
        } catch (SQLException e) {
            log.error("Failed to insert session uid={}: {}", sessionUid, e.getMessage(), e);
            seenSessions.remove(sessionUid);
        }
    }

    /**
     * Write participants on first Participants packet per sessionUid.
     */
    public void onParticipants(long sessionUid, ParticipantData[] participants) {
        if (!seenSessions.contains(sessionUid) || !seenParticipants.add(sessionUid)) {
            return;
        }
        List<DbWriter.Participant> records = new ArrayList<>(participants.length);
        for (int i = 0; i < participants.length; i++) {
            ParticipantData p = participants[i];
            records.add(new DbWriter.Participant(
                    sessionUid, i, p.name, p.teamId, p.raceNumber, p.nationality, p.aiControlled));
        }
        try (Connection conn = connectionFactory.getConnection()) {
            dbWriter.insertParticipants(conn, records);
            conn.commit();
            log.info("DB participants uid={} count={}", sessionUid, records.size());
        } catch (SQLException e) {
            log.error("Failed to insert participants uid={}: {}", sessionUid, e.getMessage(), e);
            seenParticipants.remove(sessionUid);
        }
    }

    /**
     * Write event to session_events. No deduplication — events are one-off.
     */
    public void onEvent(long sessionUid, long frameIdentifier, EventData event) {
        if (!seenSessions.contains(sessionUid)) {
            return;
        }
        Integer carIndex = event.vehicleIdx >= 0 ? event.vehicleIdx : null;
        Integer penaltySeconds = null;
        Integer otherCarIndex = null;
        Integer lapNumber = null;

        if ("PENA".equals(event.eventCode)) {
            penaltySeconds = event.time;
            otherCarIndex = event.otherVehicleIdx >= 0 ? event.otherVehicleIdx : null;
            lapNumber = event.lapNum;
        }

        DbWriter.Event record = new DbWriter.Event(
                sessionUid, frameIdentifier, event.eventCode,
                carIndex, penaltySeconds, otherCarIndex, lapNumber,
                null, null);

        try (Connection conn = connectionFactory.getConnection()) {
            dbWriter.insertEvent(conn, record);
            conn.commit();
            log.info("DB event uid={} code={} car={}", sessionUid, event.eventCode, carIndex);
        } catch (SQLException e) {
            log.error("Failed to insert event uid={} code={}: {}", sessionUid, event.eventCode, e.getMessage(), e);
        }
    }

    /**
     * Handle flashback: delete sector_snapshots and session_events recorded
     * after the flashback frame, then persist the FLBK event itself.
     */
    public void onFlashback(long sessionUid, long frameIdentifier, EventData event) {
        if (!seenSessions.contains(sessionUid)) {
            return;
        }
        long fbFrame = Integer.toUnsignedLong(event.flashbackFrameIdentifier);
        double fbTime = event.flashbackSessionTime;

        try (Connection conn = connectionFactory.getConnection()) {
            int deleted = dbWriter.deleteFlashbackData(conn, sessionUid, fbFrame);
            DbWriter.Event record = new DbWriter.Event(
                    sessionUid, frameIdentifier, "FLBK",
                    null, null, null, null,
                    fbFrame, fbTime);
            dbWriter.insertEvent(conn, record);
            conn.commit();
            log.info("DB flashback uid={} rewindToFrame={} deleted={}", sessionUid, fbFrame, deleted);
        } catch (SQLException e) {
            log.error("Failed to handle flashback uid={}: {}", sessionUid, e.getMessage(), e);
        }
    }

    /**
     * Write sector snapshots captured by the SectorTransitionDetector.
     */
    public void onSectorSnapshots(long sessionUid, List<DbWriter.SectorSnapshot> snapshots) {
        if (snapshots.isEmpty() || !seenSessions.contains(sessionUid)) {
            return;
        }
        try (Connection conn = connectionFactory.getConnection()) {
            dbWriter.insertSectorSnapshots(conn, snapshots);
            conn.commit();
        } catch (SQLException e) {
            log.error("Failed to insert {} sector snapshots: {}", snapshots.size(), e.getMessage(), e);
        }
    }

    /**
     * Write tyre sets on first TyreSets packet per session, or when a pit stop is detected.
     */
    public void onTyreSets(long sessionUid, TyreSetData.TyreSetPacket packet) {
        if (!seenSessions.contains(sessionUid)) {
            return;
        }
        List<DbWriter.TyreSet> records = new ArrayList<>(packet.sets().length);
        for (int i = 0; i < packet.sets().length; i++) {
            TyreSetData ts = packet.sets()[i];
            records.add(new DbWriter.TyreSet(
                    sessionUid, packet.carIdx(), i,
                    ts.actualTyreCompound, ts.visualTyreCompound,
                    ts.wear, ts.available, ts.lifeSpan, ts.usableLife,
                    ts.lapDeltaTime, ts.fitted));
        }
        try (Connection conn = connectionFactory.getConnection()) {
            dbWriter.insertTyreSets(conn, records);
            conn.commit();
            log.info("DB tyreSets uid={} car={} sets={}", sessionUid, packet.carIdx(), records.size());
        } catch (SQLException e) {
            log.error("Failed to insert tyre sets uid={} car={}: {}", sessionUid, packet.carIdx(), e.getMessage(), e);
        }
    }

    /**
     * Write final classifications on FinalClassification packet (once per session).
     */
    public void onFinalClassification(long sessionUid, FinalClassificationData[] cars) {
        if (!seenSessions.contains(sessionUid) || !seenFinalClassifications.add(sessionUid)) {
            return;
        }
        List<DbWriter.FinalClassification> records = new ArrayList<>(cars.length);
        for (int i = 0; i < cars.length; i++) {
            FinalClassificationData fc = cars[i];
            records.add(new DbWriter.FinalClassification(
                    sessionUid, i, fc.position, fc.gridPosition,
                    fc.numLaps, fc.points, fc.numPitStops,
                    fc.resultStatus, fc.resultReason,
                    fc.bestLapTimeInMS, fc.totalRaceTime,
                    fc.penaltiesTime, fc.numPenalties, fc.numTyreStints,
                    fc.tyreStintsActual, fc.tyreStintsVisual, fc.tyreStintsEndLaps));
        }
        try (Connection conn = connectionFactory.getConnection()) {
            dbWriter.insertFinalClassifications(conn, records);
            conn.commit();
            log.info("DB finalClassification uid={} cars={}", sessionUid, records.size());
        } catch (SQLException e) {
            log.error("Failed to insert final classifications uid={}: {}", sessionUid, e.getMessage(), e);
            seenFinalClassifications.remove(sessionUid);
        }
    }

    /**
     * Check LapData for pit stop transitions. Returns true for any car whose
     * pitStatus changed from non-pitting (0) to pitting (1 or 2), indicating
     * a new pit stop that should trigger a tyre set re-snapshot.
     */
    public boolean[] detectPitStops(LapData[] laps) {
        boolean[] triggered = new boolean[laps.length];
        for (int i = 0; i < laps.length; i++) {
            int current = laps[i].pitStatus;
            if (prevPitStatus[i] == 0 && current > 0) {
                triggered[i] = true;
            }
            prevPitStatus[i] = current;
        }
        return triggered;
    }

    // Visible for testing
    Set<Long> getSeenSessions() { return seenSessions; }
    Set<Long> getSeenParticipants() { return seenParticipants; }
    Set<Long> getSeenFinalClassifications() { return seenFinalClassifications; }
    int[] getPrevPitStatus() { return prevPitStatus; }
}
