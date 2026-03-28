package dev.victormartin.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionStateHolderTest {

    private SessionStateHolder holder;
    private RaceWebSocketHandler handler;
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        handler = new RaceWebSocketHandler();
        queueService = new QueueService(null);
        holder = new SessionStateHolder(handler, queueService);
    }

    @Test
    void initiallyNoActiveSessions() {
        assertTrue(holder.getActiveSessions().isEmpty());
        assertFalse(holder.isSessionActive("anything"));
    }

    @Test
    void sessionStartedAddsToActiveSessions() {
        holder.onSessionStarted("abc123", 5);

        var sessions = holder.getActiveSessions();
        assertEquals(1, sessions.size());
        assertEquals("abc123", sessions.getFirst().sessionUid());
        assertEquals(5, sessions.getFirst().trackId());
        assertTrue(holder.isSessionActive("abc123"));
    }

    @Test
    void sessionEndedRemovesFromActiveSessions() {
        holder.onSessionStarted("abc123", 5);
        holder.onSessionEnded("abc123");

        assertTrue(holder.getActiveSessions().isEmpty());
        assertFalse(holder.isSessionActive("abc123"));
    }

    @Test
    void multipleConcurrentSessions() {
        holder.onSessionStarted("session1", 1);
        holder.onSessionStarted("session2", 3);

        var sessions = holder.getActiveSessions();
        assertEquals(2, sessions.size());
        assertTrue(holder.isSessionActive("session1"));
        assertTrue(holder.isSessionActive("session2"));
    }

    @Test
    void endingOneSessionKeepsOthers() {
        holder.onSessionStarted("session1", 1);
        holder.onSessionStarted("session2", 3);
        holder.onSessionEnded("session1");

        var sessions = holder.getActiveSessions();
        assertEquals(1, sessions.size());
        assertEquals("session2", sessions.getFirst().sessionUid());
        assertFalse(holder.isSessionActive("session1"));
        assertTrue(holder.isSessionActive("session2"));
    }
}
