package udp.server;

import java.io.BufferedWriter;
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

    private final RaceState raceState;
    private final String host;
    private final int port;

    public TcpSender(RaceState raceState, String host, int port) {
        this.raceState = raceState;
        this.host = host;
        this.port = port;
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
