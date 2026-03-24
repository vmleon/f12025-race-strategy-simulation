package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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

    // ── Database-backed endpoints ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void listSessionsReturnsFromDb() throws Exception {
        when(jdbc.query(contains("FROM sessions"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(
                        new SessionController.SessionDto("uid1", 5, "RACE", 57, 95, "2026-03-20T14:30:00"),
                        new SessionController.SessionDto("uid2", 3, "RACE", 44, 90, "2026-03-19T10:00:00")));

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sessionUid").value("uid1"))
                .andExpect(jsonPath("$[0].trackId").value(5));
    }

    @SuppressWarnings("unchecked")
    @Test
    void listSessionsWithTrackIdFilter() throws Exception {
        when(jdbc.query(contains("track_id = ?"), any(RowMapper.class), eq(5), eq(20)))
                .thenReturn(List.of(
                        new SessionController.SessionDto("uid1", 5, "RACE", 57, 95, "2026-03-20T14:30:00")));

        mockMvc.perform(get("/api/sessions?trackId=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSessionReturns404WhenNotFound() throws Exception {
        when(jdbc.query(contains("session_uid = ?"), any(RowMapper.class), eq("unknown")))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/unknown"))
                .andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSessionReturnsDetailWithParticipants() throws Exception {
        when(jdbc.query(contains("session_uid = ?"), any(RowMapper.class), eq("uid1")))
                .thenReturn(List.of(
                        new SessionController.SessionDto("uid1", 5, "RACE", 57, 95, "2026-03-20T14:30:00")));
        when(jdbc.query(contains("FROM participants"), any(RowMapper.class), eq("uid1")))
                .thenReturn(List.of(
                        new SessionController.ParticipantDto(0, "Hamilton", 3, false),
                        new SessionController.ParticipantDto(1, "Verstappen", 1, true)));

        mockMvc.perform(get("/api/sessions/uid1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionUid").value("uid1"))
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].driverName").value("Hamilton"))
                .andExpect(jsonPath("$.participants[1].aiControlled").value(true));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSectorsReturnsData() throws Exception {
        when(jdbc.query(contains("FROM sector_snapshots"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(
                        new SessionController.SectorSnapshotDto(0, 1, 0, 28500.0, 1, "SOFT", 3, 0)));

        mockMvc.perform(get("/api/sessions/uid1/sectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].carIndex").value(0))
                .andExpect(jsonPath("$[0].sectorTimeMs").value(28500.0));
    }
}
