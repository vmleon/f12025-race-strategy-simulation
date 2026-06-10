package dev.victormartin.telemetry.engineer.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Periodic health probe for the vLLM server. The radio renderer consults
 * {@link #isHealthy()} before attempting an LLM render: when the server is down
 * the caller skips the render call (and its timeout) entirely and ships the
 * templated fallback immediately, instead of paying the render timeout on every
 * message.
 *
 * Fail-closed: {@code healthy} stays false until a probe of {@code /health}
 * returns 200, so a missing/unreachable vLLM means instant templates.
 */
@Component
public class VllmHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(VllmHealthCheck.class);
    private static final long INTERVAL_MS = 10_000;
    private static final long PROBE_TIMEOUT_MS = 2_000;

    private final String healthUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vllm-health");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean healthy = false;

    public VllmHealthCheck(
            @Value("${engineer.llm.host:localhost}") String host,
            @Value("${engineer.llm.port:8000}") int port) {
        this.healthUrl = "http://" + host + ":" + port + "/health";
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::probe, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /** True if the last probe found the vLLM server ready. */
    public boolean isHealthy() {
        return healthy;
    }

    private void probe() {
        boolean ok = false;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            ok = resp.statusCode() == 200;
        } catch (Exception e) {
            ok = false;
        }
        if (ok != healthy) {
            log.info("vLLM health changed: {} -> {} ({})", healthy, ok, healthUrl);
        }
        healthy = ok;
    }
}
