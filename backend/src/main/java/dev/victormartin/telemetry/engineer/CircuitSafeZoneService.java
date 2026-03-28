package dev.victormartin.telemetry.engineer;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class CircuitSafeZoneService {

    private final Map<Integer, List<SafeZone>> circuits = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record SafeZone(float fromMetres, float toMetres, String label) {
        boolean contains(float lapDistance) {
            return lapDistance >= fromMetres && lapDistance <= toMetres;
        }
    }

    @PostConstruct
    void loadCircuits() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:circuits/track_*.json");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    int trackId = root.get("trackId").asInt();
                    JsonNode zones = root.get("safeZones");
                    List<SafeZone> safeZones = new java.util.ArrayList<>();
                    for (JsonNode z : zones) {
                        safeZones.add(new SafeZone(
                                (float) z.get("fromMetres").asDouble(),
                                (float) z.get("toMetres").asDouble(),
                                z.has("label") ? z.get("label").asText() : ""));
                    }
                    circuits.put(trackId, List.copyOf(safeZones));
                    System.out.println("Loaded safe zones for track " + trackId
                            + " (" + root.get("name").asText() + "): " + safeZones.size() + " zones");
                }
            }
            System.out.println("CircuitSafeZoneService: loaded " + circuits.size() + " circuits");
        } catch (Exception e) {
            System.err.println("CircuitSafeZoneService: failed to load circuit configs: " + e.getMessage());
        }
    }

    /**
     * Returns true if the given lap distance on the given track is within a safe delivery zone.
     * If no config exists for the track, returns true (permissive fallback for unconfigured tracks).
     */
    public boolean isSafeToDeliver(int trackId, float lapDistance) {
        List<SafeZone> zones = circuits.get(trackId);
        if (zones == null) return true;
        for (SafeZone zone : zones) {
            if (zone.contains(lapDistance)) return true;
        }
        return false;
    }

    public boolean hasCircuit(int trackId) {
        return circuits.containsKey(trackId);
    }
}
