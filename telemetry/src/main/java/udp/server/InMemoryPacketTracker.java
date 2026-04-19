package udp.server;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryPacketTracker implements PacketTracker {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPacketTracker.class);

    private static class TypeStats {
        long received;
        long totalBytes;
        long firstSeenMillis;
        long lastSeenMillis;
    }

    private final Map<Integer, TypeStats> statsByType = new HashMap<>();
    private long totalReceived;
    private long currentSessionUID = -1;

    @Override
    public void onPacketReceived(PacketHeader header, int packetSize) {
        if (currentSessionUID != -1 && header.sessionUID != currentSessionUID) {
            log.info("Session changed (0x{} -> 0x{}), resetting stats",
                    Long.toHexString(currentSessionUID), Long.toHexString(header.sessionUID));
            statsByType.clear();
            totalReceived = 0;
        }
        currentSessionUID = header.sessionUID;

        TypeStats stats = statsByType.computeIfAbsent(header.packetId, k -> new TypeStats());

        long now = System.currentTimeMillis();
        if (stats.firstSeenMillis == 0) {
            stats.firstSeenMillis = now;
        }
        stats.lastSeenMillis = now;

        stats.received++;
        stats.totalBytes += packetSize;
        totalReceived++;
    }

    @Override
    public void printSummary() {
        log.info("=== Packet Statistics ===");
        log.info("Total received: {}", totalReceived);
        if (currentSessionUID != -1) {
            log.info("Session UID: 0x{}", Long.toHexString(currentSessionUID).toUpperCase());
        }

        log.info(String.format("%-20s %10s %12s %10s", "Type", "Received", "Avg Size", "Pkts/sec"));
        log.info("-".repeat(62));

        statsByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int packetId = entry.getKey();
                    TypeStats s = entry.getValue();
                    String name = PacketType.fromId(packetId);
                    long avgSize = s.received > 0 ? s.totalBytes / s.received : 0;
                    double pktsPerSec = packetsPerSecond(s);

                    log.info(String.format("%-20s %10d %10d B %10.1f", name, s.received, avgSize, pktsPerSec));
                });

        log.info("=========================");
    }

    private static double packetsPerSecond(TypeStats stats) {
        long durationMs = stats.lastSeenMillis - stats.firstSeenMillis;
        if (durationMs <= 0) return 0.0;
        return (stats.received * 1000.0) / durationMs;
    }
}
