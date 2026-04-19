package udp.server;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon thread that connects to the backend TCP server and sends
 * race state snapshots as newline-delimited JSON at ~1Hz.
 */
public class TcpSender implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TcpSender.class);

    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private static final String TRACE_FILE = "../logs/telemetry.trace.log";

    private final RaceState raceState;
    private final String host;
    private final int port;
    private BufferedWriter traceWriter;

    public TcpSender(RaceState raceState, String host, int port) {
        this.raceState = raceState;
        this.host = host;
        this.port = port;
        try {
            java.nio.file.Path tracePath = java.nio.file.Paths.get(TRACE_FILE);
            if (tracePath.getParent() != null) {
                java.nio.file.Files.createDirectories(tracePath.getParent());
            }
            this.traceWriter = new BufferedWriter(new FileWriter(TRACE_FILE, false));
            this.traceWriter.write("# trace started at " + System.currentTimeMillis());
            this.traceWriter.newLine();
            this.traceWriter.flush();
            log.info("Telemetry trace log: {}", TRACE_FILE);
        } catch (IOException e) {
            log.error("Failed to open trace log {}: {}", TRACE_FILE, e.getMessage(), e);
            this.traceWriter = null;
        }
    }

    private void writeTrace(String line) {
        if (traceWriter == null || line == null) return;
        try {
            traceWriter.write(line);
            traceWriter.newLine();
            traceWriter.flush();
        } catch (IOException e) {
            // Don't let trace failures kill the sender; print once and continue.
            log.warn("Trace log write failed: {}", e.getMessage());
        }
    }

    @Override
    public void run() {
        long backoff = INITIAL_BACKOFF_MS;

        while (!Thread.currentThread().isInterrupted()) {
            try (Socket socket = new Socket(host, port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

                log.info("Connected to backend TCP at {}:{}", host, port);
                backoff = INITIAL_BACKOFF_MS; // reset on successful connect
                raceState.resetSessionStartSent(); // re-send sessionStarted to new backend

                while (!Thread.currentThread().isInterrupted()) {
                    // Send lifecycle events first
                    String started = raceState.pollSessionStarted();
                    if (started != null) {
                        writer.write(started);
                        writer.newLine();
                        writer.flush();
                        log.info("TCP frame sent type=sessionStarted bytes={}", started.length());
                    }

                    String ended = raceState.pollSessionEnded();
                    if (ended != null) {
                        writer.write(ended);
                        writer.newLine();
                        writer.flush();
                        log.info("TCP frame sent type=sessionEnded bytes={}", ended.length());
                    }

                    // Send queued events
                    String event;
                    while ((event = raceState.pollEvent()) != null) {
                        writer.write(event);
                        writer.newLine();
                        writer.flush();
                        log.info("TCP frame sent type=event bytes={}", event.length());
                    }

                    // Send state snapshot
                    String line = raceState.toJsonLine();
                    if (line != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                        writeTrace(raceState.toPlayerTraceSummary());
                        log.debug("TCP frame sent type=state bytes={}", line.length());
                    }

                    Thread.sleep(1_000);
                }
            } catch (IOException e) {
                log.warn("TCP connection lost ({}), reconnecting in {}ms", e.getMessage(), backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Exponential backoff on disconnect/failure
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }
}
