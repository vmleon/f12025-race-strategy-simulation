package dev.victormartin.telemetry;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class HeartbeatWebSocketHandler extends TextWebSocketHandler {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                String payload = "{\"ts\":" + System.currentTimeMillis() + "}";
                session.sendMessage(new TextMessage(payload));
            } catch (IOException e) {
                // Connection lost, task will be cancelled on close
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
    }
}
