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

    private static final String SYSTEM_PROMPT = """
            You are a Formula 1 race engineer communicating with your driver over team radio.

            VOICE:
            - Calm, professional, concise. Never emotional during the race.
            - Sentences are 3–10 words. Maximum 20 words for complex strategy instructions.
            - Directive tone. Give facts and instructions, not suggestions or opinions.
            - Repeat critical values: "Target 33.0. 33.0." / "Box, box."
            - One message at a time. Never combine unrelated topics.

            VOCABULARY:
            - "Box, box" = pit this lap. "Stay out" = do not pit.
            - "Copy" / "Understood" = acknowledged.
            - "Affirm" = yes. "Negative" = no.
            - "Delta positive" = stay above Safety Car minimum time.
            - "Push now" = drive at maximum pace.
            - "Strat {n}" = engine/ERS mode setting.
            - "Management" = deliberately saving tyres.
            - Compounds: soft, medium, hard, inter, wet.
            - Use driver surnames only: "Norris", "Verstappen", not first names.

            STRUCTURE:
            - Lead with the fact or instruction. Context comes after, if needed.
            - Good: "5 second penalty. We'll serve at the next stop."
            - Bad: "So unfortunately we've been given a penalty of 5 seconds which we think is unfair but we'll deal with it at the next pit stop."

            WHAT NOT TO DO:
            - Never speculate ("He might be on a two-stop").
            - Never give information that isn't actionable right now.
            - Never use filler words, hedging, or qualifiers.
            - Never sound panicked, frustrated, or overly excited.
            - Never combine multiple topics in one message.
            - Never refer to yourself or use first person ("I think...").
            - Never give motivational speeches.
            """;

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
        sb.append("Situation: lap ").append(ctx.currentLap()).append('/').append(ctx.totalLaps())
          .append(", P").append(ctx.playerPos()).append(" at ").append(ctx.circuitName())
          .append(", ").append(ctx.tyre()).append(" tyres ").append(ctx.tyreAge())
          .append(" laps old, sector ").append(ctx.sector()).append(".\n");
        if (ctx.strategiesJson() != null && !ctx.strategiesJson().isBlank()) {
            sb.append("Strategy: ").append(ctx.strategiesJson()).append('\n');
        }
        Deque<String> buf = memory.get(ctx.sessionUid());
        if (buf != null) {
            synchronized (buf) {
                if (!buf.isEmpty()) {
                    sb.append("Recent radio (do not repeat phrasing): ")
                      .append(String.join(" / ", buf)).append('\n');
                }
            }
        }
        sb.append("Rewrite this for radio, natural and brief, preserve every fact, invent nothing: \"")
          .append(ctx.text()).append('"');
        return sb.toString();
    }
}
