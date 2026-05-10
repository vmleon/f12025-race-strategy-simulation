package dev.victormartin.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.simulation.LapHistoryTracker;
import dev.victormartin.telemetry.simulation.RaceSnapshot;

import static org.junit.jupiter.api.Assertions.*;

class SimulationOrchestratorTest {

    private SimulationOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        QueueService queueService = new QueueService(null);
        orchestrator = new SimulationOrchestrator(queueService, new LapHistoryTracker());
    }

    private String buildStateJson(int leaderLap, int safetyCarStatus, int car0PitStatus) {
        return """
                {"type":"state","trackId":3,"totalLaps":50,"weather":0,"trackTemp":32,"airTemp":24,
                 "safetyCarStatus":%d,"cars":[
                   {"idx":0,"pos":1,"lap":%d,"sector":0,"lastSectorMs":[28000,33000,0],
                    "tyre":"M","tyreAge":7,"pitStatus":%d,"fuel":40.0,"pits":0,
                    "fwDmg":0,"flDmg":0,"engDmg":0,"name":"Player","ai":false,"resultStatus":2},
                   {"idx":1,"pos":2,"lap":%d,"sector":1,"lastSectorMs":[28100,33100,0],
                    "tyre":"S","tyreAge":7,"pitStatus":0,"fuel":38.0,"pits":0,
                    "fwDmg":0,"flDmg":0,"engDmg":0,"name":"AI Driver","ai":true,"resultStatus":2}
                ]}""".formatted(safetyCarStatus, leaderLap, car0PitStatus, leaderLap);
    }

    @Test
    void detectTriggerOnLapCompletion() throws Exception {
        JsonNode state1 = objectMapper.readTree(buildStateJson(5, 0, 0));
        assertFalse(orchestrator.detectTrigger(state1), "First state should not trigger (baseline)");

        JsonNode state2 = objectMapper.readTree(buildStateJson(6, 0, 0));
        assertTrue(orchestrator.detectTrigger(state2), "Leader lap increase should trigger");
    }

    @Test
    void detectTriggerOnSafetyCarChange() throws Exception {
        JsonNode state1 = objectMapper.readTree(buildStateJson(5, 0, 0));
        orchestrator.detectTrigger(state1); // baseline

        JsonNode state2 = objectMapper.readTree(buildStateJson(5, 1, 0));
        assertTrue(orchestrator.detectTrigger(state2), "Safety car deploy should trigger");
    }

    @Test
    void detectTriggerOnPitStop() throws Exception {
        JsonNode state1 = objectMapper.readTree(buildStateJson(5, 0, 0));
        orchestrator.detectTrigger(state1); // baseline

        JsonNode state2 = objectMapper.readTree(buildStateJson(5, 0, 1));
        assertTrue(orchestrator.detectTrigger(state2), "Pit stop should trigger");
    }

    @Test
    void noTriggerOnSameLap() throws Exception {
        JsonNode state1 = objectMapper.readTree(buildStateJson(5, 0, 0));
        orchestrator.detectTrigger(state1); // baseline

        JsonNode state2 = objectMapper.readTree(buildStateJson(5, 0, 0));
        assertFalse(orchestrator.detectTrigger(state2), "Same state should not trigger");
    }

    @Test
    void snapshotAssembly() throws Exception {
        JsonNode state = objectMapper.readTree(buildStateJson(10, 0, 0));
        RaceSnapshot snapshot = orchestrator.assembleSnapshot(state);

        assertNotNull(snapshot);
        assertEquals(3, snapshot.trackId());
        assertEquals(50, snapshot.totalLaps());
        assertEquals(10, snapshot.currentLap());
        assertEquals(2, snapshot.cars().size());
        assertEquals("Player", snapshot.cars().get(0).driverName());
        assertFalse(snapshot.cars().get(0).aiControlled());
        assertTrue(snapshot.cars().get(1).aiControlled());
        assertEquals(17, snapshot.cars().get(0).tyreCompound()); // "M" -> 17
        assertEquals(16, snapshot.cars().get(1).tyreCompound()); // "S" -> 16
    }

    @Test
    void snapshotSkipsRetiredCars() throws Exception {
        String json = """
                {"type":"state","trackId":3,"totalLaps":50,"weather":0,"trackTemp":32,"airTemp":24,
                 "safetyCarStatus":0,"cars":[
                   {"idx":0,"pos":1,"lap":10,"sector":0,"lastSectorMs":[28000,33000,0],
                    "tyre":"M","tyreAge":7,"pitStatus":0,"fuel":40.0,"pits":0,
                    "fwDmg":0,"flDmg":0,"engDmg":0,"name":"Player","ai":false,"resultStatus":2},
                   {"idx":1,"pos":2,"lap":10,"sector":1,"lastSectorMs":[28100,33100,0],
                    "tyre":"S","tyreAge":7,"pitStatus":0,"fuel":38.0,"pits":0,
                    "fwDmg":0,"flDmg":0,"engDmg":0,"name":"Retired","ai":true,"resultStatus":4}
                ]}""";
        JsonNode state = objectMapper.readTree(json);
        RaceSnapshot snapshot = orchestrator.assembleSnapshot(state);

        assertNotNull(snapshot);
        assertEquals(1, snapshot.cars().size());
        assertEquals("Player", snapshot.cars().get(0).driverName());
    }

    @Test
    void snapshotSkipsInactiveCars() throws Exception {
        String json = """
                {"type":"state","trackId":3,"totalLaps":50,"weather":0,"trackTemp":32,"airTemp":24,
                 "safetyCarStatus":0,"cars":[
                   {"idx":0,"pos":1,"lap":10,"sector":0,"lastSectorMs":[28000,33000,0],
                    "tyre":"M","tyreAge":7,"pitStatus":0,"fuel":40.0,"pits":0,
                    "fwDmg":0,"flDmg":0,"engDmg":0,"name":"Player","ai":false,"resultStatus":2},
                   {"idx":1,"pos":0,"lap":0,"sector":0,"lastSectorMs":[0,0,0],
                    "tyre":"M","tyreAge":0,"pitStatus":0,"fuel":0.0,"pits":0,
                    "fwDmg":0,"flDmg":0,"engDmg":0,"name":"","ai":true,"resultStatus":0}
                ]}""";
        JsonNode state = objectMapper.readTree(json);
        RaceSnapshot snapshot = orchestrator.assembleSnapshot(state);

        assertNotNull(snapshot);
        assertEquals(1, snapshot.cars().size());
        assertEquals("Player", snapshot.cars().get(0).driverName());
    }

    @Test
    void resetClearsTriggerState() throws Exception {
        JsonNode state1 = objectMapper.readTree(buildStateJson(5, 0, 0));
        orchestrator.detectTrigger(state1);

        orchestrator.reset();

        // After reset, same lap as before should not trigger (baseline re-established)
        JsonNode state2 = objectMapper.readTree(buildStateJson(5, 0, 0));
        assertFalse(orchestrator.detectTrigger(state2));
    }

    @Test
    void getJobReturnsNullForUnknown() {
        assertNull(orchestrator.getJob("nonexistent"));
    }

    @Test
    void triggerNowReturnsNullWithNoState() {
        assertNull(orchestrator.triggerNow());
    }
}
