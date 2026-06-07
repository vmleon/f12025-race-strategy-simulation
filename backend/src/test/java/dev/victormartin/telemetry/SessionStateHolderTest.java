package dev.victormartin.telemetry;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionStateHolderTest {

    private SessionStateHolder holder;
    private RaceWebSocketHandler handler;
    private CapturingQueueService queueService;
    private CapturingAccuracyEvaluator accuracyEvaluator;

    /** Captures enqueued queue names so we can assert the calibration gate. */
    static class CapturingQueueService extends QueueService {
        final List<String> enqueuedQueues = new ArrayList<>();
        CapturingQueueService() { super(null); }
        @Override public void enqueue(String queueName, String jsonPayload) {
            enqueuedQueues.add(queueName);
        }
    }

    /** Captures sessions handed to the accuracy evaluator so we can assert the Race gate. */
    static class CapturingAccuracyEvaluator extends SimulationAccuracyEvaluator {
        final List<String> evaluated = new ArrayList<>();
        CapturingAccuracyEvaluator() { super(null); }
        @Override public void evaluate(String hexUid) { evaluated.add(hexUid); }
    }

    @BeforeEach
    void setUp() {
        handler = new RaceWebSocketHandler();
        queueService = new CapturingQueueService();
        accuracyEvaluator = new CapturingAccuracyEvaluator();
        holder = new SessionStateHolder(handler, queueService, accuracyEvaluator);
    }

    @Test
    void initiallyNoActiveSessions() {
        assertTrue(holder.getActiveSessions().isEmpty());
        assertFalse(holder.isSessionActive("anything"));
    }

    @Test
    void sessionStartedAddsToActiveSessions() {
        holder.onSessionStarted("abc123", 5, 1);

        var sessions = holder.getActiveSessions();
        assertEquals(1, sessions.size());
        assertEquals("abc123", sessions.getFirst().sessionUid());
        assertEquals(5, sessions.getFirst().trackId());
        assertTrue(holder.isSessionActive("abc123"));
    }

    @Test
    void sessionEndedRemovesFromActiveSessions() {
        holder.onSessionStarted("abc123", 5, 1);
        holder.onSessionEnded("abc123");

        assertTrue(holder.getActiveSessions().isEmpty());
        assertFalse(holder.isSessionActive("abc123"));
    }

    @Test
    void multipleConcurrentSessions() {
        holder.onSessionStarted("session1", 1, 1);
        holder.onSessionStarted("session2", 3, 5);

        var sessions = holder.getActiveSessions();
        assertEquals(2, sessions.size());
        assertTrue(holder.isSessionActive("session1"));
        assertTrue(holder.isSessionActive("session2"));
    }

    @Test
    void endingOneSessionKeepsOthers() {
        holder.onSessionStarted("session1", 1, 1);
        holder.onSessionStarted("session2", 3, 5);
        holder.onSessionEnded("session1");

        var sessions = holder.getActiveSessions();
        assertEquals(1, sessions.size());
        assertEquals("session2", sessions.getFirst().sessionUid());
        assertFalse(holder.isSessionActive("session1"));
        assertTrue(holder.isSessionActive("session2"));
    }

    @Test
    void practiceSessionEndEnqueuesCalibration() {
        holder.onSessionStarted("fp1", 4, 1); // sessionType 1 = Practice 1
        holder.onSessionEnded("fp1");
        assertTrue(queueService.enqueuedQueues.contains("PDBADMIN.CALIBRATION_REQUEST"));
    }

    @Test
    void nonPracticeSessionEndDoesNotCalibrate() {
        holder.onSessionStarted("race1", 4, 15); // sessionType 15 = Race
        holder.onSessionEnded("race1");
        assertFalse(queueService.enqueuedQueues.contains("PDBADMIN.CALIBRATION_REQUEST"));

        holder.onSessionStarted("q1", 4, 9); // One-Shot Qualifying
        holder.onSessionEnded("q1");
        assertFalse(queueService.enqueuedQueues.contains("PDBADMIN.CALIBRATION_REQUEST"));
    }

    @Test
    void raceSessionEndEvaluatesAccuracy() {
        holder.onSessionStarted("race1", 4, 15); // sessionType 15 = Race
        holder.onSessionEnded("race1");
        assertTrue(accuracyEvaluator.evaluated.contains("race1"));
    }

    @Test
    void practiceSessionEndDoesNotEvaluateAccuracy() {
        holder.onSessionStarted("fp1", 4, 1); // Practice
        holder.onSessionEnded("fp1");
        assertFalse(accuracyEvaluator.evaluated.contains("fp1"));
    }
}
