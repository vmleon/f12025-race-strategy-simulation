package dev.victormartin.telemetry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.engineer.RaceEngineerService;

@Component
public class TelemetryTcpServer implements CommandLineRunner {

    private final RaceWebSocketHandler raceWebSocketHandler;
    private final SessionStateHolder sessionStateHolder;
    private final SimulationOrchestrator simulationOrchestrator;
    private final StrategyOrchestrator strategyOrchestrator;
    private final RaceEngineerService raceEngineerService;
    private final QueueService queueService;
    private static final Logger log = LoggerFactory.getLogger(TelemetryTcpServer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${telemetry.tcp.port:9090}")
    private int port;

    public TelemetryTcpServer(RaceWebSocketHandler raceWebSocketHandler,
                              SessionStateHolder sessionStateHolder,
                              SimulationOrchestrator simulationOrchestrator,
                              StrategyOrchestrator strategyOrchestrator,
                              RaceEngineerService raceEngineerService,
                              QueueService queueService) {
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.sessionStateHolder = sessionStateHolder;
        this.simulationOrchestrator = simulationOrchestrator;
        this.strategyOrchestrator = strategyOrchestrator;
        this.raceEngineerService = raceEngineerService;
        this.queueService = queueService;
    }

    @Override
    public void run(String... args) {
        Thread thread = new Thread(this::acceptLoop, "telemetry-tcp-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log.info("Telemetry TCP server listening on port {}", port);

            while (!Thread.currentThread().isInterrupted()) {
                Socket client = serverSocket.accept();
                log.info("Telemetry client connected: {}", client.getRemoteSocketAddress());
                handleClient(client);
                log.info("Telemetry client disconnected: {}", client.getRemoteSocketAddress());
            }
        } catch (IOException e) {
            log.error("Telemetry TCP server error: {}", e.getMessage(), e);
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
            log.error("Error reading from telemetry client: {}", e.getMessage(), e);
        }
    }

    private void routeMessage(String line) {
        String mdcSet = null;
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            if (node.has("sessionUid")) {
                mdcSet = node.get("sessionUid").asText();
                MDC.put("sessionUid", mdcSet);
            }

            if ("state".equals(type) || type.isEmpty()) {
                log.debug("TCP frame received type={} bytes={}", type, line.length());
            } else {
                log.info("TCP frame received type={} bytes={}", type, line.length());
            }

            switch (type) {
                case "sessionStarted" -> {
                    simulationOrchestrator.reset();
                    strategyOrchestrator.reset();
                    String sessionUid = node.get("sessionUid").asText();
                    int trackId = node.get("trackId").asInt();
                    int ersAssist = node.has("ersAssist") ? node.get("ersAssist").asInt() : 0;
                    int drsAssist = node.has("drsAssist") ? node.get("drsAssist").asInt() : 0;
                    int sessionType = node.has("sessionType") ? node.get("sessionType").asInt() : 0;
                    sessionStateHolder.onSessionStarted(sessionUid, trackId);
                    raceEngineerService.onSessionStarted(sessionUid, trackId, sessionType, ersAssist, drsAssist);
                    queueService.enqueue("PDBADMIN.SESSION_LIFECYCLE", line);
                }
                case "sessionEnded" -> {
                    String endedUid = node.get("sessionUid").asText();
                    sessionStateHolder.onSessionEnded(endedUid);
                    raceEngineerService.onSessionEnded(endedUid);
                    queueService.enqueue("PDBADMIN.SESSION_LIFECYCLE", line);
                }
                case "state" -> {
                    if (node.has("sessionUid")) {
                        sessionStateHolder.onStateReceived(node.get("sessionUid").asText());
                    }
                    raceWebSocketHandler.broadcast(line);
                    simulationOrchestrator.onStateUpdate(line);
                    strategyOrchestrator.onStateUpdate(line);
                    raceEngineerService.onStateUpdate(line);
                }
                case "event" -> {
                    raceWebSocketHandler.broadcast(line);
                    simulationOrchestrator.onEvent(line);
                    strategyOrchestrator.onEvent(line);
                    raceEngineerService.onEvent(line);
                }
                default -> raceWebSocketHandler.broadcast(line);
            }
        } catch (Exception e) {
            // If parsing fails, forward as-is
            raceWebSocketHandler.broadcast(line);
        } finally {
            if (mdcSet != null) {
                MDC.remove("sessionUid");
            }
        }
    }
}
