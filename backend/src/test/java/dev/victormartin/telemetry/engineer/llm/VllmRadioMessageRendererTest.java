package dev.victormartin.telemetry.engineer.llm;

import com.sun.net.httpserver.HttpServer;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VllmRadioMessageRendererTest {

    private HttpServer server;
    private int port;
    private volatile String lastRequestBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** Respond to every chat-completions call with a fixed status + body, capturing the request. */
    private void respondWith(int status, String body) {
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
    }

    /** Respond 200 with content "r1", "r2", ... incrementing per call. */
    private void respondCounting() {
        AtomicInteger n = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body = "{\"choices\":[{\"message\":{\"content\":\"r" + n.incrementAndGet() + "\"}}]}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
    }

    private VllmRadioMessageRenderer renderer(int memorySize) {
        return new VllmRadioMessageRenderer("localhost", port, "test-model", 2500, memorySize);
    }

    private RadioRenderContext ctx(String text) {
        return new RadioRenderContext(
                "sess-1", text, Priority.NORMAL, 15, 7, "Silverstone", 3, 58, 5, "VER", "Soft", 4, 1, null);
    }

    @Test
    void returnsModelTextOnSuccess() {
        respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"Box now, box.\"}}]}");
        assertEquals("Box now, box.", renderer(5).render(ctx("Box this lap")));
    }

    @Test
    void includesSituationAndOriginalInPrompt() {
        respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        renderer(5).render(ctx("Box this lap"));
        assertTrue(lastRequestBody.contains("Silverstone"));
        assertTrue(lastRequestBody.contains("Box this lap"));
    }

    @Test
    void fallsBackToTemplateOnNon200() {
        respondWith(500, "boom");
        assertEquals("Box this lap", renderer(5).render(ctx("Box this lap")));
    }

    @Test
    void fallsBackToTemplateOnMalformedJson() {
        respondWith(200, "not json at all");
        assertEquals("Box this lap", renderer(5).render(ctx("Box this lap")));
    }

    @Test
    void fallsBackToTemplateOnEmptyContent() {
        respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}");
        assertEquals("Box this lap", renderer(5).render(ctx("Box this lap")));
    }

    @Test
    void fallsBackToTemplateOnConnectionFailure() {
        server.stop(0);          // nothing listening on `port` now
        server = null;
        assertEquals("Box this lap", renderer(5).render(ctx("Box this lap")));
    }

    @Test
    void rollingMemoryTrimsToSizeAndFeedsPrompt() {
        respondCounting();
        VllmRadioMessageRenderer r = renderer(2);   // keep last 2
        r.render(ctx("a"));   // -> r1, memory [r1]
        r.render(ctx("b"));   // -> r2, request#2 saw [r1]
        assertTrue(lastRequestBody.contains("r1"));
        r.render(ctx("c"));   // -> r3, request#3 saw [r1, r2]
        r.render(ctx("d"));   // -> r4, request#4 saw [r2, r3]; r1 evicted
        assertTrue(lastRequestBody.contains("r2"));
        assertTrue(lastRequestBody.contains("r3"));
        assertFalse(lastRequestBody.contains("r1"));
    }
}
