package dev.victormartin.telemetry;

/**
 * Seam for persisting simulation / strategy-evaluation I/O to
 * {@code simulation_run_io} — the inputs a run was given and the full result it
 * produced (the diagnostic record the GP postmortem had to reverse-engineer).
 * Rows are keyed by {@code job_id}, the correlation key shared by
 * {@code simulation_runs}, {@code radio_messages} and the request/result queues.
 * The production implementation ({@link JdbcSimulationIoLog}) writes
 * asynchronously and best-effort; tests supply a capturing implementation.
 * Implementations must never throw.
 */
public interface SimulationIoLog {

    /** Record a run's request at enqueue time. {@code kind} is {@code AUTO} or {@code STRATEGY}. */
    void recordRequest(String jobId, String kind, String requestJson, int playerCarIndex);

    /** Update the matching row with the result when it returns. {@code playerMeanPosition} may be null. */
    void recordResult(String jobId, String resultJson, Double playerMeanPosition);
}
