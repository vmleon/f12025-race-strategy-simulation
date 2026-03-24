package dev.victormartin.telemetry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class TelemetryTcpServer implements CommandLineRunner {

    private final RaceWebSocketHandler raceWebSocketHandler;

    @Value("${telemetry.tcp.port:9090}")
    private int port;

    public TelemetryTcpServer(RaceWebSocketHandler raceWebSocketHandler) {
        this.raceWebSocketHandler = raceWebSocketHandler;
    }

    @Override
    public void run(String... args) {
        Thread thread = new Thread(this::acceptLoop, "telemetry-tcp-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Telemetry TCP server listening on port " + port);

            while (!Thread.currentThread().isInterrupted()) {
                Socket client = serverSocket.accept();
                System.out.println("Telemetry client connected: " + client.getRemoteSocketAddress());
                handleClient(client);
                System.out.println("Telemetry client disconnected: " + client.getRemoteSocketAddress());
            }
        } catch (IOException e) {
            System.err.println("Telemetry TCP server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket client) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                // Forward all messages to WebSocket broadcast
                raceWebSocketHandler.broadcast(line);
            }
        } catch (IOException e) {
            System.err.println("Error reading from telemetry client: " + e.getMessage());
        }
    }
}
