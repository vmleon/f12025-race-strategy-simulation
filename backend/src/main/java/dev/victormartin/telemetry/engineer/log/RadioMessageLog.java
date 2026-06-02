package dev.victormartin.telemetry.engineer.log;

/**
 * Seam for persisting delivered radio messages. The production implementation
 * ({@link JdbcRadioMessageLog}) writes asynchronously and best-effort; tests
 * supply a capturing implementation.
 */
public interface RadioMessageLog {

    /** Record a delivered message. Implementations must never throw. */
    void record(RadioMessageLogEntry entry);
}
