package udp.server;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates database writes for session lifecycle data.
 * Tracks seen session UIDs to deduplicate one-off writes (sessions,
 * participants, final classifications). Events are written immediately
 * without deduplication. Tyre sets are re-snapshotted on pit stop detection.
 */
public class LifecycleDispatcher {

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
            System.out.printf("  DB session uid=%d track=%d%n", sessionUid, session.trackId);
        } catch (SQLException e) {
            System.err.printf("Failed to insert session uid=%d: %s%n", sessionUid, e.getMessage());
            seenSessions.remove(sessionUid);
        }
    }

    /**
     * Write participants on first Participants packet per sessionUid.
     */
    public void onParticipants(long sessionUid, ParticipantData[] participants) {
        if (!seenParticipants.add(sessionUid)) {
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
            System.out.printf("  DB participants uid=%d count=%d%n", sessionUid, records.size());
        } catch (SQLException e) {
            System.err.printf("Failed to insert participants uid=%d: %s%n", sessionUid, e.getMessage());
            seenParticipants.remove(sessionUid);
        }
    }

    /**
     * Write event to session_events. No deduplication — events are one-off.
     */
    public void onEvent(long sessionUid, long frameIdentifier, EventData event) {
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
            System.out.printf("  DB event uid=%d code=%s car=%s%n",
                    sessionUid, event.eventCode, carIndex);
        } catch (SQLException e) {
            System.err.printf("Failed to insert event uid=%d code=%s: %s%n",
                    sessionUid, event.eventCode, e.getMessage());
        }
    }

    /**
     * Handle flashback: delete sector_snapshots and session_events recorded
     * after the flashback frame, then persist the FLBK event itself.
     */
    public void onFlashback(long sessionUid, long frameIdentifier, EventData event) {
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
            System.out.printf("  DB flashback uid=%d rewindToFrame=%d deleted=%d%n",
                    sessionUid, fbFrame, deleted);
        } catch (SQLException e) {
            System.err.printf("Failed to handle flashback uid=%d: %s%n",
                    sessionUid, e.getMessage());
        }
    }

    /**
     * Write sector snapshots captured by the SectorTransitionDetector.
     */
    public void onSectorSnapshots(List<DbWriter.SectorSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        try (Connection conn = connectionFactory.getConnection()) {
            dbWriter.insertSectorSnapshots(conn, snapshots);
            conn.commit();
        } catch (SQLException e) {
            System.err.printf("Failed to insert %d sector snapshots: %s%n",
                    snapshots.size(), e.getMessage());
        }
    }

    /**
     * Write tyre sets on first TyreSets packet per session, or when a pit stop is detected.
     */
    public void onTyreSets(long sessionUid, TyreSetData.TyreSetPacket packet) {
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
            System.out.printf("  DB tyreSets uid=%d car=%d sets=%d%n",
                    sessionUid, packet.carIdx(), records.size());
        } catch (SQLException e) {
            System.err.printf("Failed to insert tyre sets uid=%d car=%d: %s%n",
                    sessionUid, packet.carIdx(), e.getMessage());
        }
    }

    /**
     * Write final classifications on FinalClassification packet (once per session).
     */
    public void onFinalClassification(long sessionUid, FinalClassificationData[] cars) {
        if (!seenFinalClassifications.add(sessionUid)) {
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
            System.out.printf("  DB finalClassification uid=%d cars=%d%n", sessionUid, records.size());
        } catch (SQLException e) {
            System.err.printf("Failed to insert final classifications uid=%d: %s%n",
                    sessionUid, e.getMessage());
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
