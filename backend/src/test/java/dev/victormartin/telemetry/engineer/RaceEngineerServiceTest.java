package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.engineer.CircuitSafeZoneService;
import dev.victormartin.telemetry.engineer.RaceEngineerWebSocketHandler;
import dev.victormartin.telemetry.engineer.log.RadioMessageLog;
import dev.victormartin.telemetry.engineer.llm.PassthroughRadioMessageRenderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link RaceEngineerService}. Drives the orchestrator
 * with synthetic JSON state messages crafted to reproduce each Phase C bug,
 * and asserts the orchestrator no longer misfires.
 *
 * The assertions look at the queue (via {@code queueFor(...)}) so we don't
 * have to rely on the {@link CircuitSafeZoneService} delivery filter — that
 * layer is shared and not under test here.
 */
class RaceEngineerServiceTest {

    private static final int TRACK_ID = 7;
    private static final String SESSION_UID = "v2-test";

    private RaceEngineerService service;
    private List<String> broadcasts;

    @BeforeEach
    void setUp() {
        // Always-in-zone stub so HIGH/NORMAL messages can deliver in tests
        // without loading per-circuit safe-zone JSON.
        CircuitSafeZoneService safeZone = new CircuitSafeZoneService() {
            @Override
            public int currentZoneIndex(int trackId, float lapDistance, int speedKmh) {
                return 0;
            }
        };
        broadcasts = new ArrayList<>();
        RaceEngineerWebSocketHandler handler = new RaceEngineerWebSocketHandler() {
            @Override public void broadcast(String json) { broadcasts.add(json); }
        };
        RadioMessageLog noopLog = entry -> { };
        PassthroughRadioMessageRenderer renderer =
                new PassthroughRadioMessageRenderer("localhost", 8000, "test-model");
        service = new RaceEngineerService(
                safeZone, handler, noopLog, renderer, 500L, Runnable::run);
    }

    /** Builds a one-car JSON state line. {@code aiCar} optional second car (may be null). */
    private static String stateJson(int sessionType, int currentLap, int totalLaps,
                                     int trackLength, int playerPos, float playerLapDist,
                                     int playerSpeed, float playerThrottle,
                                     int playerPitStatus,
                                     String aiCarJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"state\",\"trackId\":").append(TRACK_ID)
          .append(",\"totalLaps\":").append(totalLaps)
          .append(",\"trackLength\":").append(trackLength)
          .append(",\"sessionType\":").append(sessionType)
          .append(",\"cars\":[")
          .append("{\"idx\":0,\"name\":\"Player\",\"ai\":false,\"pos\":").append(playerPos)
          .append(",\"lap\":").append(currentLap)
          .append(",\"lapDist\":").append(playerLapDist)
          .append(",\"speed\":").append(playerSpeed)
          .append(",\"throttle\":").append(playerThrottle)
          .append(",\"pitStatus\":").append(playerPitStatus)
          .append(",\"pitLaneTimerActive\":0,\"pitLaneTimeMs\":0}");
        if (aiCarJson != null) {
            sb.append(',').append(aiCarJson);
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String aiCar(int idx, String name, int pos, int lap, float lapDist, int pitStatus) {
        return "{\"idx\":" + idx + ",\"name\":\"" + name + "\",\"ai\":true,\"pos\":" + pos
                + ",\"lap\":" + lap + ",\"lapDist\":" + lapDist + ",\"pitStatus\":" + pitStatus
                + ",\"speed\":280,\"throttle\":0.9}";
    }

    private List<String> messageTexts() {
        List<String> out = new ArrayList<>();
        // Drain queue: peek into it indirectly by polling all enqueued.
        // We can't iterate a PriorityQueue fairly, so we use the trace via broadcasts:
        // RaceEngineerService only broadcasts when something is delivered, so we
        // need an alternative. Instead, expose enqueued via a small reflection-free
        // helper: keep snapshotting before delivery happens by checking queue.size.
        // Simpler: parse broadcasts.
        for (String b : broadcasts) {
            int t = b.indexOf("\"text\":\"");
            if (t < 0) continue;
            int end = b.indexOf('"', t + 8);
            out.add(b.substring(t + 8, end));
        }
        return out;
    }

    // --- Bug 3.1: "Track is clear" must NOT fire on pit ENTRY ---------------

    @Test
    void doesNotFireTrackClearOnPitEntryDuringFreePractice() {
        service.onSessionStarted(SESSION_UID, TRACK_ID, 4, 0, 0);

        // Tick 1: on track, pre-pit-entry. Throttle high so slow-lap detector won't trip.
        service.onStateUpdate(stateJson(4, 3, 0, 5300, 5, 4500f, 240, 0.9f, 0,
                aiCar(1, "ALONSO", 6, 3, 4500f - 1000f, 0)));

        // Tick 2: just crossed pit-entry line (pitStatus 0 → 1). Now in PIT_ENTRY.
        // The historical bug fired here. The orchestrator must not.
        service.onStateUpdate(stateJson(4, 3, 0, 5300, 5, 4630f, 80, 0.4f, 1,
                aiCar(1, "ALONSO", 6, 3, 4630f - 1000f, 0)));

        // Tick 3: rolling deeper into pit lane. Still PIT_ENTRY.
        service.onStateUpdate(stateJson(4, 3, 0, 5300, 5, 200f, 30, 0.2f, 1,
                aiCar(1, "ALONSO", 6, 3, 0f, 0)));

        // No "Track is clear" should have been broadcast yet.
        assertFalse(messageTexts().stream().anyMatch(t -> t.contains("Track is clear")),
                "Track-clear message must not fire on pit ENTRY; got: " + messageTexts());
    }

    @Test
    void firesTrackClearOnceWhenLeavingTheBox() {
        service.onSessionStarted(SESSION_UID, TRACK_ID, 4, 0, 0);

        // Sequence of ticks: on track → pit entry → stopped → exit.
        service.onStateUpdate(stateJson(4, 3, 0, 5300, 5, 4500f, 240, 0.9f, 0,
                aiCar(1, "ALONSO", 6, 3, 1000f, 0)));
        service.onStateUpdate(stateJson(4, 3, 0, 5300, 5, 4630f, 80, 0.4f, 1,
                aiCar(1, "ALONSO", 6, 3, 1000f, 0)));
        service.onStateUpdate(stateJson(4, 3, 0, 5300, 5, 49f, 0, 0f, 1,
                aiCar(1, "ALONSO", 6, 3, 1000f, 0)));
        // Releasing — speed picks up, transitions to PIT_EXIT. ALONSO is far.
        service.onStateUpdate(stateJson(4, 3, 0, 5300, 5, 60f, 20, 0.5f, 1,
                aiCar(1, "ALONSO", 6, 3, 1000f, 0)));

        long n = messageTexts().stream().filter(t -> t.contains("Track is clear")).count();
        assertEquals(1, n, "Track-clear must fire exactly once on PIT_EXIT; got: " + messageTexts());
    }

    // --- Bug 3.2: "X closing fast behind" must NOT fire while in box --------

    @Test
    void doesNotFireSlowLapWarningWhileParkedInBox() {
        service.onSessionStarted(SESSION_UID, TRACK_ID, 4, 0, 0);

        // Eight ticks: spawn in garage, throttle 0, RIVAL behind very close & closing.
        // A throttle-only "slow lap" with no pit gate would fire here. The orchestrator must not.
        for (int i = 0; i < 8; i++) {
            service.onStateUpdate(stateJson(4, 1, 0, 5300, 5, -4615.2f, 0, 0f, 1,
                    aiCar(1, "RIVAL", 6, 1, -4715f - i * 10, 0)));
        }

        assertFalse(messageTexts().stream().anyMatch(t -> t.contains("closing fast behind")),
                "Slow-lap warning must not fire while in box; got: " + messageTexts());
    }

    // --- Bug 3.5: SCAR uses currentLap baseline -----------------------------

    @Test
    void scarEventUsesCurrentLapAsBaselineNotZero() {
        service.onSessionStarted(SESSION_UID, TRACK_ID, 10, 0, 0);

        // Drive a few state updates so currentLap progresses to 4.
        for (int lap = 1; lap <= 4; lap++) {
            service.onStateUpdate(stateJson(10, lap, 50, 5300, 5, 100f, 240, 0.9f, 0, null));
        }

        // Trigger SCAR event.
        service.onEvent("{\"event\":\"SCAR\",\"trackId\":" + TRACK_ID + "}");

        // The SCAR message should be in the queue with createdAtLap=4 (i.e. it
        // does NOT immediately expire when polled at currentLap=4 with ttlLaps=3).
        // We verify by polling a few more ticks at lap 4 and observing the
        // safety-car message gets enqueued (queue size > 0 right after the event).
        // Easiest: invoke pollForDelivery indirectly via another state update at
        // lap 4 — message should still be live.
        service.onStateUpdate(stateJson(10, 4, 50, 5300, 5, 110f, 240, 0.9f, 0, null));

        assertTrue(messageTexts().stream().anyMatch(t -> t.contains("Safety car deployed")),
                "SCAR with currentLap baseline should survive immediate poll; got: " + messageTexts());
    }

    // --- RTMT: name the driver, drop "watch for debris" ----------------------

    @Test
    void rtmtNamesDriverAndDropsDebris() {
        service.onSessionStarted(SESSION_UID, TRACK_ID, 10, 0, 0);
        // Fire RTMT first so it's within the per-zone NORMAL delivery budget,
        // then one state tick to trigger delivery.
        service.onEvent("{\"event\":\"RTMT\",\"trackId\":" + TRACK_ID
                + ",\"driverName\":\"Verstappen\"}");
        service.onStateUpdate(stateJson(10, 1, 50, 5300, 5, 110f, 240, 0.9f, 0, null));

        assertTrue(messageTexts().stream().anyMatch(t -> t.contains("Verstappen has retired")),
                "RTMT should name the retiring driver; got: " + messageTexts());
        assertFalse(messageTexts().stream().anyMatch(t -> t.contains("debris")),
                "RTMT should not mention debris; got: " + messageTexts());
    }

    // --- Sanity: race session works without exceptions -----------------------

    @Test
    void raceSessionProcessesPitStopWithoutSpuriousMessages() {
        service.onSessionStarted(SESSION_UID, TRACK_ID, 10, 0, 0);

        // On-track racing.
        for (int i = 0; i < 3; i++) {
            service.onStateUpdate(stateJson(10, 1 + i, 17, 5300, 5, 1000f + i * 100, 270, 0.95f, 0,
                    aiCar(1, "RIVAL", 6, 1 + i, 800f + i * 100, 0)));
        }

        // Pit cycle: 0 → 1 → 2 → 1 → 0
        service.onStateUpdate(stateJson(10, 4, 17, 5300, 5, 4609f, 90, 0.4f, 1,
                aiCar(1, "RIVAL", 6, 4, 4500f, 0)));
        service.onStateUpdate(stateJson(10, 4, 17, 5300, 5, 52.4f, 8, 0.0f, 2,
                aiCar(1, "RIVAL", 6, 4, 4600f, 0)));
        service.onStateUpdate(stateJson(10, 4, 17, 5300, 5, 52.7f, 12, 0.3f, 1,
                aiCar(1, "RIVAL", 6, 4, 4700f, 0)));
        service.onStateUpdate(stateJson(10, 4, 17, 5300, 5, 298.8f, 95, 0.95f, 0,
                aiCar(1, "RIVAL", 6, 4, 4800f, 0)));

        // Race + pit visit should not have fired any practice/quali-only messages.
        assertFalse(messageTexts().stream().anyMatch(t -> t.contains("Track is clear")),
                "Track-clear is practice/quali only");
        assertFalse(messageTexts().stream().anyMatch(t -> t.contains("closing fast behind")),
                "Slow-lap warning is practice/quali only");
    }
}
