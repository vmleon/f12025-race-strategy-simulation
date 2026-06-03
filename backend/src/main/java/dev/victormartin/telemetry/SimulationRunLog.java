package dev.victormartin.telemetry;

/**
 * Seam for persisting simulation-run lifecycle rows to {@code simulation_runs}.
 * The production implementation ({@link JdbcSimulationRunLog}) writes
 * asynchronously and best-effort; tests supply a capturing implementation.
 * Implementations must never throw.
 */
public interface SimulationRunLog {

    /** Record a run starting (status {@code running}). */
    void recordStarted(String jobId, String sessionUid, long startedAtEpochMs);

    /** Record a run reaching a terminal state ({@code completed}, {@code failed}, {@code timeout}). */
    void recordCompleted(String jobId, long durationMs, int iterations, String status);
}
