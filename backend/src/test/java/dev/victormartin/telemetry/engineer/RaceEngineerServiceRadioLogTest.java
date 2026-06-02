package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.engineer.CircuitSafeZoneService;
import dev.victormartin.telemetry.engineer.RaceEngineerWebSocketHandler;
import dev.victormartin.telemetry.engineer.log.RadioMessageLog;
import dev.victormartin.telemetry.engineer.log.RadioMessageLogEntry;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.RankedStrategy;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.StrategyCandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a delivered radio message produces exactly one log entry with
 * the expected situational context, that the strategy column is null when no
 * evaluation exists, and that a throwing log never breaks delivery.
 */
class RaceEngineerServiceRadioLogTest {

    private static final int TRACK_ID = 7;
    private static final String SESSION_UID = "12345";
    private static final int SESSION_TYPE_RACE = 15;

    private static CircuitSafeZoneService alwaysInZone() {
        return new CircuitSafeZoneService() {
            @Override
            public int currentZoneIndex(int trackId, float lapDistance, int speedKmh) {
                return 0;
            }
        };
    }

    private static RaceEngineerWebSocketHandler capturing(List<String> sink) {
        return new RaceEngineerWebSocketHandler() {
            @Override public void broadcast(String json) { sink.add(json); }
        };
    }

    // These tests rely on SessionStartGreetingDetector firing (and the always-in-zone
    // stub letting it through) on the first ON_TRACK tick, so onStateUpdate delivers at
    // least one message that gets logged.

    /** One player car, on track, lap 1, with a tyre + sector so context is populated. */
    private static String stateJson(int currentLap) {
        return "{\"type\":\"state\",\"trackId\":" + TRACK_ID
                + ",\"totalLaps\":58,\"trackLength\":5000,\"sessionType\":" + SESSION_TYPE_RACE
                + ",\"cars\":[{\"idx\":0,\"name\":\"P\",\"ai\":false,\"pos\":5,\"lap\":" + currentLap
                + ",\"lapDist\":1234.5,\"speed\":280,\"throttle\":1.0,\"pitStatus\":0,"
                + "\"pitLaneTimerActive\":0,\"pitLaneTimeMs\":0,\"sector\":1,"
                + "\"tyre\":\"Soft\",\"tyreAge\":4}]}";
    }

    @Test
    void deliveredMessageIsLoggedWithContextAndNullStrategy() {
        List<RadioMessageLogEntry> logged = new ArrayList<>();
        List<String> broadcasts = new ArrayList<>();
        RaceEngineerService service = new RaceEngineerService(
                alwaysInZone(), capturing(broadcasts), logged::add);

        service.onSessionStarted(SESSION_UID, TRACK_ID, SESSION_TYPE_RACE, 0, 0);
        service.onStateUpdate(stateJson(1));

        assertFalse(logged.isEmpty(), "a delivered message should be logged");
        RadioMessageLogEntry e = logged.get(0);
        assertEquals(SESSION_UID, e.sessionUid());
        assertEquals(TRACK_ID, e.trackId());
        assertEquals(SESSION_TYPE_RACE, e.sessionType());
        assertEquals(1, e.lapNumber());
        assertEquals(5, e.playerPosition());
        assertEquals(1, e.sector());
        assertEquals("Soft", e.tyreCompound());
        assertEquals(4, e.tyreAgeLaps());
        assertFalse(e.messageText().isBlank());
        assertEquals(e.messageText(), e.renderedText(),
                "passthrough renders the original text unchanged");
        assertFalse(e.priority().isBlank());
        assertNull(e.bestStrategiesJson(), "no strategy pushed yet -> null");
    }

    @Test
    void strategyEvaluationIsCapturedInLog() {
        List<RadioMessageLogEntry> logged = new ArrayList<>();
        RaceEngineerService service = new RaceEngineerService(
                alwaysInZone(), capturing(new ArrayList<>()), logged::add);

        service.onSessionStarted(SESSION_UID, TRACK_ID, SESSION_TYPE_RACE, 0, 0);
        StrategyEvaluation eval = new StrategyEvaluation(0, List.of(
                new RankedStrategy(1, new StrategyCandidate("1-stop: Soft->Hard", List.of()),
                        4.1, 0, 0, 0, 0, 0, 0, 12.0)));
        service.onStrategyEvaluation(1, eval);
        service.onStateUpdate(stateJson(1));

        assertFalse(logged.isEmpty());
        assertTrue(logged.get(0).bestStrategiesJson().contains("1-stop: Soft->Hard"),
                "logged strategy json should contain the top label");
    }

    @Test
    void throwingLogDoesNotBreakDelivery() {
        List<String> broadcasts = new ArrayList<>();
        RadioMessageLog throwing = entry -> { throw new RuntimeException("boom"); };
        RaceEngineerService service = new RaceEngineerService(
                alwaysInZone(), capturing(broadcasts), throwing);

        service.onSessionStarted(SESSION_UID, TRACK_ID, SESSION_TYPE_RACE, 0, 0);
        service.onStateUpdate(stateJson(1));

        assertTrue(broadcasts.stream().anyMatch(b -> b.contains("\"type\":\"raceEngineer\"")),
                "delivery must still happen even if logging throws");
    }
}
