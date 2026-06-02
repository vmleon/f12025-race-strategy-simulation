package dev.victormartin.telemetry.engineer.llm;

import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;

/**
 * Everything the renderer is allowed to see about one outgoing radio message.
 * {@code text} is the original templated string. The remaining fields are the
 * live race context the LLM impl will turn into a prompt (phase 2); the
 * passthrough placeholder ignores them. {@code strategiesJson} is null when no
 * strategy evaluation exists yet (e.g. Practice).
 */
public record RadioRenderContext(
        String text,
        Priority priority,
        int sessionType,
        int trackId,
        int currentLap,
        int totalLaps,
        int playerPos,
        String tyre,
        int tyreAge,
        int sector,
        String strategiesJson
) {}
