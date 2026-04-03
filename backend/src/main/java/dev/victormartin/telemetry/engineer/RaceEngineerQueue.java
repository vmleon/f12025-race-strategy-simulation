package dev.victormartin.telemetry.engineer;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Per-session in-memory priority queue for race engineer messages.
 * Messages are ordered by priority (IMMEDIATE > HIGH > NORMAL), then by creation time.
 */
public class RaceEngineerQueue {

    private static final int NORMAL_BUDGET_PER_LAP = 4;

    private static final Comparator<EngineerMessage> MESSAGE_ORDER =
            Comparator.comparingInt((EngineerMessage m) -> m.priority().ordinal())
                    .thenComparingLong(EngineerMessage::createdAt);

    private final PriorityQueue<EngineerMessage> queue = new PriorityQueue<>(MESSAGE_ORDER);

    private int budgetLap = -1;
    private int normalDelivered = 0;

    public synchronized void enqueue(EngineerMessage message) {
        queue.add(message);
    }

    /**
     * Returns the highest-priority pending message if delivery conditions are met:
     * - IMMEDIATE messages are returned regardless of track position or budget.
     * - HIGH messages are returned only in a safe zone (no budget limit).
     * - NORMAL messages are returned only in a safe zone and within the per-lap budget.
     * Expired messages are discarded silently.
     * Returns null if nothing to deliver.
     */
    public synchronized EngineerMessage pollForDelivery(float lapDistance, int trackId,
                                                         int currentLap,
                                                         CircuitSafeZoneService safeZoneService) {
        boolean inSafeZone = safeZoneService.isSafeToDeliver(trackId, lapDistance);

        // Reset budget on new lap
        if (currentLap != budgetLap) {
            budgetLap = currentLap;
            normalDelivered = 0;
        }

        // Drain expired messages
        queue.removeIf(m -> m.isExpired(currentLap));

        // Try to find a deliverable message
        if (queue.isEmpty()) return null;

        EngineerMessage top = queue.peek();
        if (top.priority() == EngineerMessage.Priority.IMMEDIATE) {
            return queue.poll();
        }
        if (inSafeZone) {
            if (top.priority() == EngineerMessage.Priority.HIGH) {
                return queue.poll();
            }
            if (normalDelivered < NORMAL_BUDGET_PER_LAP) {
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
