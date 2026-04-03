package dev.victormartin.telemetry.engineer;

import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerQueueTest {

    private static CircuitSafeZoneService safeZoneService;
    private RaceEngineerQueue queue;

    @BeforeAll
    static void loadCircuits() {
        safeZoneService = new CircuitSafeZoneService();
        safeZoneService.loadCircuits();
    }

    @BeforeEach
    void setUp() {
        queue = new RaceEngineerQueue();
    }

    @Test
    void normalBudgetIsPerZoneNotPerLap() {
        // Enqueue 4 NORMAL messages
        for (int i = 0; i < 4; i++) {
            queue.enqueue(new EngineerMessage(Priority.NORMAL, "msg" + i, System.currentTimeMillis(), 1, 5));
        }

        // In zone 0 (lapDist=200, Melbourne), deliver 2 then budget exhausted
        assertNotNull(queue.pollForDelivery(200f, 0, 1, 0, safeZoneService));
        assertNotNull(queue.pollForDelivery(200f, 0, 1, 0, safeZoneService));
        assertNull(queue.pollForDelivery(200f, 0, 1, 0, safeZoneService), "Budget should be exhausted in zone 0");

        // Move to zone 1 (lapDist=1200), budget resets
        assertNotNull(queue.pollForDelivery(1200f, 0, 1, 0, safeZoneService), "Budget should reset in zone 1");
        assertNotNull(queue.pollForDelivery(1200f, 0, 1, 0, safeZoneService));

        // 4 messages delivered total across 2 zones, queue empty
        assertEquals(0, queue.size());
    }

    @Test
    void highPriorityBypassesZoneBudget() {
        // Enqueue 3 NORMAL to fill budget, then add HIGH
        queue.enqueue(new EngineerMessage(Priority.NORMAL, "n1", System.currentTimeMillis(), 1, 5));
        queue.enqueue(new EngineerMessage(Priority.NORMAL, "n2", System.currentTimeMillis(), 1, 5));
        queue.enqueue(new EngineerMessage(Priority.NORMAL, "n3", System.currentTimeMillis(), 1, 5));

        // Deliver 2 NORMAL (budget exhausted)
        assertNotNull(queue.pollForDelivery(200f, 0, 1, 0, safeZoneService));
        assertNotNull(queue.pollForDelivery(200f, 0, 1, 0, safeZoneService));

        // Budget exhausted, NORMAL should not deliver
        assertNull(queue.pollForDelivery(200f, 0, 1, 0, safeZoneService), "NORMAL budget should be exhausted");

        // Now add a HIGH message — it should deliver despite exhausted budget
        queue.enqueue(new EngineerMessage(Priority.HIGH, "h1", System.currentTimeMillis(), 1, 5));
        EngineerMessage msg = queue.pollForDelivery(200f, 0, 1, 0, safeZoneService);
        assertNotNull(msg, "HIGH should bypass zone budget");
        assertEquals(Priority.HIGH, msg.priority());
    }

    @Test
    void immediateDeliversOutsideSafeZone() {
        queue.enqueue(new EngineerMessage(Priority.IMMEDIATE, "urgent", System.currentTimeMillis(), 1, 5));

        // lapDist=500 is outside all Melbourne safe zones
        EngineerMessage msg = queue.pollForDelivery(500f, 0, 1, 0, safeZoneService);
        assertNotNull(msg, "IMMEDIATE should deliver outside safe zones");
        assertEquals(Priority.IMMEDIATE, msg.priority());
    }

    @Test
    void normalDoesNotDeliverOutsideSafeZone() {
        queue.enqueue(new EngineerMessage(Priority.NORMAL, "msg", System.currentTimeMillis(), 1, 5));

        // lapDist=500 is outside all Melbourne safe zones
        assertNull(queue.pollForDelivery(500f, 0, 1, 0, safeZoneService));
    }
}
