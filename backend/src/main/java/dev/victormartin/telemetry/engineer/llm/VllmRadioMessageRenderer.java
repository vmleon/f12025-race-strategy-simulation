package dev.victormartin.telemetry.engineer.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Rewords a radio message via a vLLM OpenAI-compatible {@code /v1/chat/completions}
 * call. On any failure (non-200, timeout, parse error, empty content) it returns the
 * original templated text so the driver always hears something — the same fallback the
 * async funnel in {@code RaceEngineerService} also provides. Keeps a bounded per-session
 * ring buffer of recently delivered lines and feeds them to the prompt so phrasing varies.
 *
 * Active only when {@code engineer.llm.enabled=true}; otherwise
 * {@link PassthroughRadioMessageRenderer} is the bean.
 */
@Component
@ConditionalOnProperty(name = "engineer.llm.enabled", havingValue = "true")
public class VllmRadioMessageRenderer implements RadioMessageRenderer {

    private static final Logger log = LoggerFactory.getLogger(VllmRadioMessageRenderer.class);

    // Tightened against the live gemma-4 endpoint (see design/08 §6). The earlier
    // verbatim voice block caused two faithfulness defects: the model addressed the
    // driver by name and swapped generic references ("leader") for invented surnames.
    // These rules fix both while keeping the calm, concise voice.
    private static final String SYSTEM_PROMPT = """
            You are a Formula 1 race engineer speaking to your driver on team radio. \
            Calm, professional, concise. One short sentence, 3-10 words. \
            Never address your own driver by name. \
            Keep any driver surname that the message contains. \
            Never introduce a driver name and never replace a generic reference such \
            as 'leader' or 'car ahead' with a name. \
            Never invent facts and never drop facts: keep every position, place, gap, \
            lap number and name from the message, and add nothing that is not in it.""";

    private final String endpoint;
    private final String model;
    private final long timeoutMs;
    private final int memorySize;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;
    private final Map<String, Deque<String>> memory = new ConcurrentHashMap<>();

    public VllmRadioMessageRenderer(
            @Value("${engineer.llm.host:localhost}") String host,
            @Value("${engineer.llm.port:8000}") int port,
            @Value("${engineer.llm.model:google/gemma-4-E4B-it}") String model,
            @Value("${engineer.llm.timeout-ms:2500}") long timeoutMs,
            @Value("${engineer.llm.memory-size:5}") int memorySize) {
        this.endpoint = "http://" + host + ":" + port + "/v1/chat/completions";
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.memorySize = memorySize;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        log.info("Radio LLM renderer = vLLM. endpoint={} model={} timeoutMs={} memorySize={}",
                endpoint, model, timeoutMs, memorySize);
    }

    @Override
    public String render(RadioRenderContext ctx) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(ctx)))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("vLLM non-200 {} — using template", resp.statusCode());
                return ctx.text();
            }
            JsonNode content = mapper.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                return ctx.text();
            }
            String rendered = content.asText().trim();
            remember(ctx.sessionUid(), rendered);
            return rendered;
        } catch (Exception e) {
            log.warn("vLLM render failed ({}) — using template", e.toString());
            return ctx.text();
        }
    }

    private void remember(String sessionUid, String rendered) {
        if (sessionUid == null || memorySize <= 0) return;
        Deque<String> buf = memory.computeIfAbsent(sessionUid, k -> new ArrayDeque<>());
        synchronized (buf) {
            buf.addLast(rendered);
            while (buf.size() > memorySize) buf.removeFirst();
        }
    }

    private String buildRequestBody(RadioRenderContext ctx) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.put("temperature", 0.4);
        root.put("max_tokens", 40);
        ArrayNode messages = root.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", buildUserPrompt(ctx));
        return mapper.writeValueAsString(root);
    }

    private String buildUserPrompt(RadioRenderContext ctx) {
        StringBuilder sb = new StringBuilder();
        // Circuit name is intentionally omitted: it is fixed and common knowledge to
        // both driver and engineer, adds no phrasing value, and only risks the model
        // reading it back as situational chatter.
        sb.append("CONTEXT (background only — do NOT read this back and do NOT turn it into new facts):\n");
        sb.append("lap ").append(ctx.currentLap()).append('/').append(ctx.totalLaps())
          .append(", P").append(ctx.playerPos())
          .append(", ").append(ctx.tyre()).append(" tyres ").append(ctx.tyreAge())
          .append(" laps old, sector ").append(ctx.sector()).append(".\n");
        if (ctx.strategiesJson() != null && !ctx.strategiesJson().isBlank()) {
            sb.append("strategy: ").append(ctx.strategiesJson()).append('\n');
        }
        Deque<String> buf = memory.get(ctx.sessionUid());
        if (buf != null) {
            synchronized (buf) {
                if (!buf.isEmpty()) {
                    sb.append("recently said (vary your wording): ")
                      .append(String.join(" / ", buf)).append('\n');
                }
            }
        }
        sb.append("\nRewrite ONLY the message below as one short, natural radio call. ")
          .append("Keep every fact it contains and add nothing that is not in it. ")
          .append("Output just the radio line, nothing else.\n")
          .append("MESSAGE: \"").append(ctx.text()).append('"');
        return sb.toString();
    }
}
