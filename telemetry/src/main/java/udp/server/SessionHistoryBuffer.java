package udp.server;

/**
 * In-memory buffer of SessionHistory data for Tier 2 UDP loss recovery.
 * Stores the last N laps of game-validated sector times per car.
 */
public class SessionHistoryBuffer {

    private static final int NUM_CARS = 22;
    private static final int BUFFER_LAPS = 5;

    // Per-car, per-lap sector times (millis). Index: [car][ring_position]
    private final SessionHistoryData.LapHistory[][] buffer = new SessionHistoryData.LapHistory[NUM_CARS][BUFFER_LAPS];
    private final int[] latestLap = new int[NUM_CARS]; // most recent lap number stored

    public SessionHistoryBuffer() {
        reset();
    }

    public void reset() {
        for (int i = 0; i < NUM_CARS; i++) {
            latestLap[i] = 0;
            for (int j = 0; j < BUFFER_LAPS; j++) {
                buffer[i][j] = null;
            }
        }
    }

    /**
     * Update buffer with incoming SessionHistory packet for one car.
     */
    public void update(SessionHistoryData history) {
        int car = history.carIdx;
        if (car < 0 || car >= NUM_CARS) return;

        int numLaps = history.numLaps;
        latestLap[car] = numLaps;

        // Store the last BUFFER_LAPS entries
        int start = Math.max(0, numLaps - BUFFER_LAPS);
        for (int lap = start; lap < numLaps; lap++) {
            int ringIdx = lap % BUFFER_LAPS;
            buffer[car][ringIdx] = history.lapHistories[lap];
        }
    }

    /**
     * Look up a sector time from the buffer.
     * @param carIndex car index (0-21)
     * @param lapNumber 1-based lap number
     * @param sectorNumber 0, 1, or 2
     * @return sector time in ms, or -1 if not available
     */
    public long getSectorTime(int carIndex, int lapNumber, int sectorNumber) {
        if (carIndex < 0 || carIndex >= NUM_CARS) return -1;
        if (lapNumber < 1) return -1;

        // SessionHistory uses 0-based lap index
        int lapIdx = lapNumber - 1;
        int ringIdx = lapIdx % BUFFER_LAPS;

        SessionHistoryData.LapHistory entry = buffer[carIndex][ringIdx];
        if (entry == null) return -1;

        // Verify this is actually the right lap by checking if it's within range
        if (lapNumber > latestLap[carIndex] || lapNumber <= latestLap[carIndex] - BUFFER_LAPS) {
            return -1; // lap too old or not yet received
        }

        return switch (sectorNumber) {
            case 0 -> entry.sector1TimeInMS();
            case 1 -> entry.sector2TimeInMS();
            case 2 -> entry.sector3TimeInMS();
            default -> -1;
        };
    }
}
