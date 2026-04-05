package dev.victormartin.telemetry.engineer;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;

/**
 * Per-session in-memory priority queue for race engineer messages.
 * Messages are ordered by priority (IMMEDIATE > HIGH > NORMAL), then by creation time.
 */
public class RaceEngineerQueue {

    private static final int NORMAL_BUDGET_PER_ZONE = 2;

    private static final Comparator<EngineerMessage> MESSAGE_ORDER =
            Comparator.comparingInt((EngineerMessage m) -> m.priority().ordinal())
                    .thenComparingLong(EngineerMessage::createdAt);

    private final PriorityQueue<EngineerMessage> queue = new PriorityQueue<>(MESSAGE_ORDER);

    private int currentZone = -1;
    private int normalDelivered = 0;

    public synchronized void enqueue(EngineerMessage message) {
        queue.add(message);
    }

    /**
     * Returns the highest-priority pending message if delivery conditions are met:
     * - IMMEDIATE messages are returned regardless of track position or budget.
     * - HIGH messages are returned only in a safe zone (no budget limit).
     * - NORMAL messages are returned only in a safe zone and within the per-zone budget.
     * Expired messages are discarded silently.
     * Returns null if nothing to deliver.
     */
    public synchronized EngineerMessage pollForDelivery(float lapDistance, int trackId,
                                                         int currentLap, int speedKmh,
                                                         CircuitSafeZoneService safeZoneService) {
        int zoneIndex = safeZoneService.currentZoneIndex(trackId, lapDistance, speedKmh);

        // Reset budget when entering a new zone
        if (zoneIndex != currentZone) {
            currentZone = zoneIndex;
            normalDelivered = 0;
        }

        // Drain expired messages (lap-based TTL and wall-clock TTL), logging each drop
        long now = System.currentTimeMillis();
        Iterator<EngineerMessage> it = queue.iterator();
        while (it.hasNext()) {
            EngineerMessage m = it.next();
            boolean ttlLapsExpired = m.isExpired(currentLap);
            boolean wallClockStale = m.isStale();
            if (ttlLapsExpired || wallClockStale) {
                String reason = ttlLapsExpired ? "expired_ttl_laps" : "expired_wall_clock";
                System.out.println("MESSAGE_DROP reason=" + reason
                        + " priority=" + m.priority()
                        + " ageMs=" + (now - m.createdAt())
                        + " zoneIndex=" + zoneIndex
                        + " createdAtLap=" + m.createdAtLap()
                        + " currentLap=" + currentLap
                        + " ttlLaps=" + m.ttlLaps()
                        + " normalDeliveredThisZone=" + normalDelivered);
                it.remove();
            }
        }

        // Try to find a deliverable message
        if (queue.isEmpty()) return null;

        EngineerMessage top = queue.peek();
        if (top.priority() == EngineerMessage.Priority.IMMEDIATE) {
            return queue.poll();
        }
        if (zoneIndex >= 0) {
            if (top.priority() == EngineerMessage.Priority.HIGH) {
                return queue.poll();
            }
            if (normalDelivered < NORMAL_BUDGET_PER_ZONE) {
                normalDelivered++;
                return queue.poll();
            }
        }
        return null;
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
    }
}
