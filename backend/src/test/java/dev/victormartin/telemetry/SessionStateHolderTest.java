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
    void initiallyNoActiveSession() {
        assertFalse(holder.isSessionActive());
        assertNull(holder.getActiveSession());
    }

    @Test
    void sessionStartedSetsActiveState() {
        holder.onSessionStarted("abc123", 5);

        assertTrue(holder.isSessionActive());
        var info = holder.getActiveSession();
        assertNotNull(info);
        assertEquals("abc123", info.sessionUid());
        assertEquals(5, info.trackId());
    }

    @Test
    void sessionEndedClearsActiveState() {
        holder.onSessionStarted("abc123", 5);
        holder.onSessionEnded("abc123");

        assertFalse(holder.isSessionActive());
        assertNull(holder.getActiveSession());
    }

    @Test
    void newSessionReplacesOld() {
        holder.onSessionStarted("session1", 1);
        holder.onSessionStarted("session2", 3);

        assertTrue(holder.isSessionActive());
        assertEquals("session2", holder.getActiveSession().sessionUid());
        assertEquals(3, holder.getActiveSession().trackId());
    }
}
