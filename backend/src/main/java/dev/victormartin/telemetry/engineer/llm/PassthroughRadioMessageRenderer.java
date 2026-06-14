package dev.victormartin.telemetry.engineer.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder renderer: returns the templated text unchanged. Holds the vLLM
 * connection target (host/port/model) and logs it once at startup to prove the
 * wiring; the real {@code /v1/chat/completions} call lands in a future
 * {@code VllmRadioMessageRenderer} (phase 2). See
 * {@code todos/02-LLM-RADIO-MESSAGE-GENERATION.md}.
 */
@Component
@ConditionalOnProperty(name = "engineer.llm.enabled", havingValue = "false", matchIfMissing = true)
public class PassthroughRadioMessageRenderer implements RadioMessageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PassthroughRadioMessageRenderer.class);

    private final String host;
    private final int port;
    private final String model;

    public PassthroughRadioMessageRenderer(
            @Value("${engineer.llm.host:localhost}") String host,
            @Value("${engineer.llm.port:8000}") int port,
            @Value("${engineer.llm.model:meta-llama/Llama-3.1-8B-Instruct}") String model) {
        this.host = host;
        this.port = port;
        this.model = model;
        log.info("Radio LLM renderer = passthrough (no calls yet). vLLM target host={} port={} model={}",
                host, port, model);
    }

    @Override
    public String render(RadioRenderContext ctx) {
        return ctx.text();
    }
}
