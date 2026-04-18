package dev.victormartin.telemetry.engineer;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-session in-memory priority queue for race engineer messages.
 * Messages are ordered by priority (IMMEDIATE > HIGH > NORMAL), then by creation time.
 */
public class RaceEngineerQueue {

    private static final Logger TRACE = LoggerFactory.getLogger("engineer.trace");

    private static final int NORMAL_BUDGET_PER_ZONE = 2;

    private static final Comparator<EngineerMessage> MESSAGE_ORDER =
            Comparator.comparingInt((EngineerMessage m) -> m.priority().ordinal())
                    .thenComparingLong(EngineerMessage::createdAt);

    private final PriorityQueue<EngineerMessage> queue = new PriorityQueue<>(MESSAGE_ORDER);

    private int currentZone = -1;
    private int normalDelivered = 0;

    public synchronized void enqueue(EngineerMessage message) {
        queue.add(message);
        TRACE.debug("ENQUEUE priority={} createdAtLap={} ttlLaps={} text=\"{}\"",
                message.priority(), message.createdAtLap(), message.ttlLaps(), message.text());
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
                TRACE.debug("DROP reason={} priority={} ageMs={} zoneIndex={} createdAtLap={} currentLap={} ttlLaps={} normalDeliveredThisZone={} text=\"{}\"",
                        reason, m.priority(), (now - m.createdAt()), zoneIndex,
                        m.createdAtLap(), currentLap, m.ttlLaps(), normalDelivered, m.text());
                it.remove();
            }
        }

        // Try to find a deliverable message
        if (queue.isEmpty()) {
            TRACE.debug("POLL queueSize=0 zoneIndex={} lapDistance={} speedKmh={}",
                    zoneIndex, lapDistance, speedKmh);
            return null;
        }

        EngineerMessage top = queue.peek();
        if (top.priority() == EngineerMessage.Priority.IMMEDIATE) {
            TRACE.debug("DELIVER priority=IMMEDIATE zoneIndex={} queueSize={} text=\"{}\"",
                    zoneIndex, queue.size(), top.text());
            return queue.poll();
        }
        if (zoneIndex >= 0) {
            if (top.priority() == EngineerMessage.Priority.HIGH) {
                TRACE.debug("DELIVER priority=HIGH zoneIndex={} queueSize={} text=\"{}\"",
                        zoneIndex, queue.size(), top.text());
                return queue.poll();
            }
            if (normalDelivered < NORMAL_BUDGET_PER_ZONE) {
                normalDelivered++;
                TRACE.debug("DELIVER priority=NORMAL zoneIndex={} normalDeliveredThisZone={} queueSize={} text=\"{}\"",
                        zoneIndex, normalDelivered, queue.size(), top.text());
                return queue.poll();
            }
            TRACE.debug("DEFER reason=normal_budget_exhausted priority={} zoneIndex={} normalDeliveredThisZone={} queueSize={} text=\"{}\"",
                    top.priority(), zoneIndex, normalDelivered, queue.size(), top.text());
            return null;
        }
        TRACE.debug("DEFER reason=no_safe_zone priority={} lapDistance={} speedKmh={} queueSize={} text=\"{}\"",
                top.priority(), lapDistance, speedKmh, queue.size(), top.text());
        return null;
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
    }
}
