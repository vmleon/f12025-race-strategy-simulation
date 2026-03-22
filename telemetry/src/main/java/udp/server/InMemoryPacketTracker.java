package udp.server;

import java.util.HashMap;
import java.util.Map;

public class InMemoryPacketTracker implements PacketTracker {

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
            System.out.println("\n--- Session changed (0x" + Long.toHexString(currentSessionUID)
                    + " -> 0x" + Long.toHexString(header.sessionUID) + "), resetting stats ---\n");
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
        System.out.println();
        System.out.println("=== Packet Statistics ===");
        System.out.printf("Total received: %d%n", totalReceived);
        if (currentSessionUID != -1) {
            System.out.printf("Session UID: 0x%X%n", currentSessionUID);
        }
        System.out.println();

        System.out.printf("%-20s %10s %12s %10s%n",
                "Type", "Received", "Avg Size", "Pkts/sec");
        System.out.println("-".repeat(62));

        statsByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int packetId = entry.getKey();
                    TypeStats s = entry.getValue();
                    String name = PacketType.fromId(packetId);
                    long avgSize = s.received > 0 ? s.totalBytes / s.received : 0;
                    double pktsPerSec = packetsPerSecond(s);

                    System.out.printf("%-20s %10d %10d B %10.1f%n",
                            name, s.received, avgSize, pktsPerSec);
                });

        System.out.println("=========================");
    }

    private static double packetsPerSecond(TypeStats stats) {
        long durationMs = stats.lastSeenMillis - stats.firstSeenMillis;
        if (durationMs <= 0) return 0.0;
        return (stats.received * 1000.0) / durationMs;
    }
}
