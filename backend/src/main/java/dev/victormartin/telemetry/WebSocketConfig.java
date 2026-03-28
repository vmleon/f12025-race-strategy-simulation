package dev.victormartin.telemetry;

import dev.victormartin.telemetry.engineer.RaceEngineerWebSocketHandler;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RaceWebSocketHandler raceWebSocketHandler;
    private final RaceEngineerWebSocketHandler raceEngineerWebSocketHandler;

    public WebSocketConfig(RaceWebSocketHandler raceWebSocketHandler,
                           RaceEngineerWebSocketHandler raceEngineerWebSocketHandler) {
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.raceEngineerWebSocketHandler = raceEngineerWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new HeartbeatWebSocketHandler(), "/ws/heartbeat")
                .setAllowedOrigins("*");
        registry.addHandler(raceWebSocketHandler, "/ws/race")
                .setAllowedOrigins("*");
        registry.addHandler(raceEngineerWebSocketHandler, "/ws/race-engineer")
                .setAllowedOrigins("*");
    }
}
