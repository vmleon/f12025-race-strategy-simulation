package dev.victormartin.telemetry.engineer.detectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RaceFinishDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ArrayNode grid(int active, int inactiveSlots) {
        ArrayNode cars = MAPPER.createArrayNode();
        for (int i = 0; i < active; i++) {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("resultStatus", 2); // active
            cars.add(c);
        }
        for (int i = 0; i < inactiveSlots; i++) {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("resultStatus", i % 2); // 0 = invalid, 1 = inactive
            cars.add(c);
        }
        return cars;
    }

    @Test
    void activeGridSizeExcludesUnusedSlots() {
        // The game sends 22 car slots; only 20 are a real grid.
        assertEquals(20, RaceFinishDetector.activeGridSize(grid(20, 2)));
    }

    @Test
    void lastPlaceUsesRealGridNotSlotCount() {
        // With the real grid of 20, finishing P20 is dead last → the "rough day"
        // line, not the midfield/back-of-pack message a 22 count would give.
        assertEquals(
                "P20. Rough day at the office. Box this lap and we regroup.",
                RaceFinishDetector.buildFinishMessage(20, 20));
    }
}
