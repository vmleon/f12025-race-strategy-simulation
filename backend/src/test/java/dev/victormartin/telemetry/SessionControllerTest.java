package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void activeReturns404WhenNoSession() throws Exception {
        when(sessionStateHolder.getActiveSession()).thenReturn(null);

        mockMvc.perform(get("/api/sessions/active"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeReturnsSessionMetadata() throws Exception {
        when(sessionStateHolder.getActiveSession())
                .thenReturn(new SessionStateHolder.SessionInfo("abc123", 5));

        mockMvc.perform(get("/api/sessions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionUid").value("abc123"))
                .andExpect(jsonPath("$.trackId").value(5));
    }

    @Test
    void activeStateReturns404WhenNoSession() throws Exception {
        when(sessionStateHolder.isSessionActive()).thenReturn(false);

        mockMvc.perform(get("/api/sessions/active/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeStateReturns404WhenNoStateYet() throws Exception {
        when(sessionStateHolder.isSessionActive()).thenReturn(true);
        when(raceWebSocketHandler.getLatestState()).thenReturn(null);

        mockMvc.perform(get("/api/sessions/active/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeStateReturnsLatestState() throws Exception {
        when(sessionStateHolder.isSessionActive()).thenReturn(true);
        when(raceWebSocketHandler.getLatestState()).thenReturn("{\"cars\":[]}");

        mockMvc.perform(get("/api/sessions/active/state"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"cars\":[]}"));
    }
}
