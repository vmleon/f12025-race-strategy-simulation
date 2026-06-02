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
                "Box this lap", Priority.NORMAL, 15, 7, 3, 58, 5, "Soft", 4, 1, null);

        assertEquals("Box this lap", renderer.render(ctx));
    }
}
