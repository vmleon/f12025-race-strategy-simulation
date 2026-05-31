package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SessionStateHolder sessionStateHolder;

    @MockBean
    private RaceWebSocketHandler raceWebSocketHandler;

    @MockBean
    private JdbcTemplate jdbc;

    // ── Active session endpoints ─────────────────────────────────────────

    @Test
    void activeReturnsEmptyListWhenNoSession() throws Exception {
        when(sessionStateHolder.getActiveSessions()).thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    void activeReturnsEnrichedSessions() throws Exception {
        when(sessionStateHolder.getActiveSessions())
                .thenReturn(List.of(
                        new SessionStateHolder.SessionInfo("abc123", 5),
                        new SessionStateHolder.SessionInfo("def456", 3)));

        when(jdbc.queryForObject(contains("session_type"), eq(Integer.class), eq("abc123")))
                .thenReturn(10);
        when(jdbc.queryForObject(contains("session_type"), eq(Integer.class), eq("def456")))
                .thenReturn(1);

        mockMvc.perform(get("/api/sessions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sessionUid").value("abc123"))
                .andExpect(jsonPath("$[0].trackName").value("Monaco"))
                .andExpect(jsonPath("$[0].sessionType").value("Race"))
                .andExpect(jsonPath("$[0].live").value(true))
                .andExpect(jsonPath("$[1].sessionUid").value("def456"))
                .andExpect(jsonPath("$[1].trackName").value("Bahrain"))
                .andExpect(jsonPath("$[1].sessionType").value("Practice 1"))
                .andExpect(jsonPath("$[1].live").value(true));
    }

    @SuppressWarnings("unchecked")
    @Test
    void activeSortsLiveSessionsFirst() throws Exception {
        long now = System.currentTimeMillis();
        when(sessionStateHolder.getActiveSessions())
                .thenReturn(List.of(
                        new SessionStateHolder.SessionInfo("stale1", 5, now - 60_000),
                        new SessionStateHolder.SessionInfo("live1", 3, now)));

        mockMvc.perform(get("/api/sessions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionUid").value("live1"))
                .andExpect(jsonPath("$[0].live").value(true))
                .andExpect(jsonPath("$[1].sessionUid").value("stale1"))
                .andExpect(jsonPath("$[1].live").value(false));
    }

    @SuppressWarnings("unchecked")
    @Test
    void activeReturnsFallbackWhenDbUnavailable() throws Exception {
        when(sessionStateHolder.getActiveSessions())
                .thenReturn(List.of(new SessionStateHolder.SessionInfo("abc123", 5)));

        when(jdbc.queryForObject(contains("session_type"), eq(Integer.class), eq("abc123")))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        mockMvc.perform(get("/api/sessions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sessionUid").value("abc123"))
                .andExpect(jsonPath("$[0].trackName").value("Monaco"))
                .andExpect(jsonPath("$[0].sessionType").value(""));
    }

    @Test
    void activeStateReturns404WhenNoSession() throws Exception {
        when(sessionStateHolder.getActiveSessions()).thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/active/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeStateReturns404WhenNoStateYet() throws Exception {
        when(sessionStateHolder.getActiveSessions())
                .thenReturn(List.of(new SessionStateHolder.SessionInfo("abc123", 5)));
        when(raceWebSocketHandler.getLatestState()).thenReturn(null);

        mockMvc.perform(get("/api/sessions/active/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeStateReturnsLatestState() throws Exception {
        when(sessionStateHolder.getActiveSessions())
                .thenReturn(List.of(new SessionStateHolder.SessionInfo("abc123", 5)));
        when(raceWebSocketHandler.getLatestState()).thenReturn("{\"cars\":[]}");

        mockMvc.perform(get("/api/sessions/active/state"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"cars\":[]}"));
    }
}
