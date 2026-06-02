package dev.victormartin.telemetry.engineer.llm;

/**
 * Rewords a radio message in the race-engineer voice before broadcast. The
 * call MAY block (network) in real implementations — the orchestrator runs it
 * off the telemetry thread with a timeout. Implementations must never return
 * null; if they cannot render, they return {@code ctx.text()} (the original).
 */
public interface RadioMessageRenderer {
    String render(RadioRenderContext ctx);
}
