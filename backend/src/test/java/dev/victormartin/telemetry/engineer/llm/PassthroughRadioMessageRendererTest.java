package dev.victormartin.telemetry.engineer.llm;

import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PassthroughRadioMessageRendererTest {

    @Test
    void returnsTextUnchanged() {
        PassthroughRadioMessageRenderer renderer =
                new PassthroughRadioMessageRenderer("localhost", 8000, "test-model");
        RadioRenderContext ctx = new RadioRenderContext(
                "test-session", "Box this lap", Priority.NORMAL, 15, 7, "Silverstone", 3, 58, 5, "VER", "Soft", 4, 1, null);

        assertEquals("Box this lap", renderer.render(ctx));
    }
}
