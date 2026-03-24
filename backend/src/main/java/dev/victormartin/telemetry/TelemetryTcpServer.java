package dev.victormartin.telemetry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class TelemetryTcpServer implements CommandLineRunner {

    private final RaceWebSocketHandler raceWebSocketHandler;
    private final SessionStateHolder sessionStateHolder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${telemetry.tcp.port:9090}")
    private int port;

    public TelemetryTcpServer(RaceWebSocketHandler raceWebSocketHandler, SessionStateHolder sessionStateHolder) {
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.sessionStateHolder = sessionStateHolder;
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
                routeMessage(line);
            }
        } catch (IOException e) {
            System.err.println("Error reading from telemetry client: " + e.getMessage());
        }
    }

    private void routeMessage(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "sessionStarted" -> sessionStateHolder.onSessionStarted(
                        node.get("sessionUid").asText(),
                        node.get("trackId").asInt());
                case "sessionEnded" -> sessionStateHolder.onSessionEnded(
                        node.get("sessionUid").asText());
                default -> raceWebSocketHandler.broadcast(line);
            }
        } catch (Exception e) {
            // If parsing fails, forward as-is
            raceWebSocketHandler.broadcast(line);
        }
    }
}
