package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameMappingsTest {

    @Test
    void knownTrackIds() {
        assertEquals("Melbourne", GameMappings.trackName(0));
        assertEquals("Monaco", GameMappings.trackName(5));
        assertEquals("Silverstone", GameMappings.trackName(7));
        assertEquals("Monza", GameMappings.trackName(11));
        assertEquals("Jeddah", GameMappings.trackName(29));
        assertEquals("Las Vegas", GameMappings.trackName(31));
    }

    @Test
    void unknownTrackIdFallback() {
        assertEquals("Track 99", GameMappings.trackName(99));
    }

    @Test
    void knownSessionTypes() {
        assertEquals("Practice 1", GameMappings.sessionTypeName(1));
        assertEquals("Qualifying 1", GameMappings.sessionTypeName(5));
        assertEquals("Race", GameMappings.sessionTypeName(10));
        assertEquals("Time Trial", GameMappings.sessionTypeName(13));
    }

    @Test
    void unknownSessionTypeFallback() {
        assertEquals("Type 99", GameMappings.sessionTypeName(99));
    }
}
