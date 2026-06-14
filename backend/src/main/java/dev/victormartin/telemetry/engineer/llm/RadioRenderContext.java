package dev.victormartin.telemetry.engineer.llm;

import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;

/**
 * Everything the renderer is allowed to see about one outgoing radio message.
 * {@code text} is the original templated string. The remaining fields are the
 * live race context the LLM impl will turn into a prompt (phase 2); the
 * passthrough placeholder ignores them. {@code circuitName} is resolved from
 * {@code trackId}; {@code driverName} is the player car's name (null if the
 * telemetry has not supplied one). {@code strategiesJson} is null when no
 * strategy evaluation exists yet (e.g. Practice). {@code sessionUid} identifies
 * the session so the renderer can keep per-session rolling memory.
 */
public record RadioRenderContext(
        String sessionUid,
        String text,
        Priority priority,
        int sessionType,
        int trackId,
        String circuitName,
        int currentLap,
        int totalLaps,
        int playerPos,
        String driverName,
        String tyre,
        int tyreAge,
        int sector,
        String strategiesJson
) {}
