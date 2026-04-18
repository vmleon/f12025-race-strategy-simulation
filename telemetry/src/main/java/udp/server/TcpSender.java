package udp.server;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Daemon thread that connects to the backend TCP server and sends
 * race state snapshots as newline-delimited JSON at ~1Hz.
 */
public class TcpSender implements Runnable {

    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private static final String TRACE_FILE = "telemetry.trace.log";

    private final RaceState raceState;
    private final String host;
    private final int port;
    private BufferedWriter traceWriter;

    public TcpSender(RaceState raceState, String host, int port) {
        this.raceState = raceState;
        this.host = host;
        this.port = port;
        try {
            this.traceWriter = new BufferedWriter(new FileWriter(TRACE_FILE, true));
            this.traceWriter.write("# trace started at " + System.currentTimeMillis());
            this.traceWriter.newLine();
            this.traceWriter.flush();
            System.out.println("Telemetry trace log: " + TRACE_FILE);
        } catch (IOException e) {
            System.err.println("Failed to open trace log " + TRACE_FILE + ": " + e.getMessage());
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
            System.err.println("Trace log write failed: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        long backoff = INITIAL_BACKOFF_MS;

        while (!Thread.currentThread().isInterrupted()) {
            try (Socket socket = new Socket(host, port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

                System.out.println("Connected to backend TCP at " + host + ":" + port);
                backoff = INITIAL_BACKOFF_MS; // reset on successful connect
                raceState.resetSessionStartSent(); // re-send sessionStarted to new backend

                while (!Thread.currentThread().isInterrupted()) {
                    // Send lifecycle events first
                    String started = raceState.pollSessionStarted();
                    if (started != null) {
                        writer.write(started);
                        writer.newLine();
                        writer.flush();
                    }

                    String ended = raceState.pollSessionEnded();
                    if (ended != null) {
                        writer.write(ended);
                        writer.newLine();
                        writer.flush();
                    }

                    // Send queued events
                    String event;
                    while ((event = raceState.pollEvent()) != null) {
                        writer.write(event);
                        writer.newLine();
                        writer.flush();
                    }

                    // Send state snapshot
                    String line = raceState.toJsonLine();
                    if (line != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                        writeTrace(raceState.toPlayerTraceSummary());
                    }

                    Thread.sleep(1_000);
                }
            } catch (IOException e) {
                System.err.println("TCP connection lost (" + e.getMessage() + "), reconnecting in " + backoff + "ms");
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
