package dev.victormartin.telemetry.engineer;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Per-session in-memory priority queue for race engineer messages.
 * Messages are ordered by priority (IMMEDIATE > HIGH > NORMAL), then by creation time.
 */
public class RaceEngineerQueue {

    private static final Comparator<EngineerMessage> MESSAGE_ORDER =
            Comparator.comparingInt((EngineerMessage m) -> m.priority().ordinal())
                    .thenComparingLong(EngineerMessage::createdAt);

    private final PriorityQueue<EngineerMessage> queue = new PriorityQueue<>(MESSAGE_ORDER);

    public synchronized void enqueue(EngineerMessage message) {
        queue.add(message);
    }

    /**
     * Returns the highest-priority pending message if delivery conditions are met:
     * - IMMEDIATE messages are returned regardless of track position.
     * - HIGH/NORMAL messages are returned only if the player is in a safe zone.
     * Expired messages are discarded silently.
     * Returns null if nothing to deliver.
     */
    public synchronized EngineerMessage pollForDelivery(float lapDistance, int trackId,
                                                         int currentLap,
                                                         CircuitSafeZoneService safeZoneService) {
        boolean inSafeZone = safeZoneService.isSafeToDeliver(trackId, lapDistance);

        // Drain expired messages
        queue.removeIf(m -> m.isExpired(currentLap));

        // Try to find a deliverable message
        if (queue.isEmpty()) return null;

        EngineerMessage top = queue.peek();
        if (top.priority() == EngineerMessage.Priority.IMMEDIATE) {
            return queue.poll();
        }
        if (inSafeZone) {
            return queue.poll();
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
