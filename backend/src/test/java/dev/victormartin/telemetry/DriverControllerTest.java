package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DriverController.class)
class DriverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbc;

    // ── List ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void listDriversReturnsAll() throws Exception {
        when(jdbc.query(contains("FROM drivers"), any(RowMapper.class)))
                .thenReturn(List.of(
                        new DriverController.DriverDto(1, "Alice", "alice@test.com", "2026-03-20T10:00:00", 2),
                        new DriverController.DriverDto(2, "Bob", null, "2026-03-21T11:00:00", 0)));

        mockMvc.perform(get("/api/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[1].email").isEmpty());
    }

    // ── Get by ID ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void getDriverReturns404WhenNotFound() throws Exception {
        when(jdbc.query(contains("FROM drivers d WHERE"), any(RowMapper.class), eq(99L)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/drivers/99"))
                .andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getDriverReturnsDetailWithSessions() throws Exception {
        when(jdbc.query(contains("FROM drivers d WHERE"), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(
                        new DriverController.DriverDto(1, "Alice", "alice@test.com", "2026-03-20T10:00:00", 1)));
        when(jdbc.query(contains("JOIN sessions s ON"), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(
                        new DriverController.DriverSessionDto("12345", 0, 5, "RACE", "2026-03-20T14:00:00")));

        mockMvc.perform(get("/api/drivers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].sessionUid").value("12345"));
    }

    // ── Create ───────────────────────────────────────────────────────────

    @Test
    void createDriverReturns201() throws Exception {
        doAnswer(invocation -> {
            KeyHolder kh = invocation.getArgument(1);
            ((GeneratedKeyHolder) kh).getKeyList().add(Map.of("driver_id", 1L));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        mockMvc.perform(post("/api/drivers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice\",\"email\":\"alice@test.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.driverId").value(1))
                .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    void createDriverReturns400WhenNameBlank() throws Exception {
        mockMvc.perform(post("/api/drivers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDriverReturns409OnDuplicateName() throws Exception {
        doAnswer(invocation -> {
            throw new DuplicateKeyException("unique constraint violated");
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        mockMvc.perform(post("/api/drivers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice\"}"))
                .andExpect(status().isConflict());
    }

    // ── Update ───────────────────────────────────────────────────────────

    @Test
    void updateDriverReturnsOk() throws Exception {
        when(jdbc.update(contains("UPDATE drivers"), eq("Bob"), eq((String) null), eq(1L)))
                .thenReturn(1);

        mockMvc.perform(put("/api/drivers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bob"));
    }

    @Test
    void updateDriverReturns404WhenNotFound() throws Exception {
        when(jdbc.update(contains("UPDATE drivers"), any(), any(), eq(99L)))
                .thenReturn(0);

        mockMvc.perform(put("/api/drivers/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bob\"}"))
                .andExpect(status().isNotFound());
    }

    // ── Delete ───────────────────────────────────────────────────────────

    @Test
    void deleteDriverReturns204() throws Exception {
        when(jdbc.update(contains("FROM drivers"), eq(1L))).thenReturn(1);

        mockMvc.perform(delete("/api/drivers/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteDriverReturns404WhenNotFound() throws Exception {
        when(jdbc.update(contains("FROM drivers"), eq(99L))).thenReturn(0);

        mockMvc.perform(delete("/api/drivers/99"))
                .andExpect(status().isNotFound());
    }

    // ── Associate session ────────────────────────────────────────────────

    @Test
    void associateSessionReturns201() throws Exception {
        when(jdbc.queryForObject(contains("FROM drivers"), eq(Integer.class), eq(1L)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("FROM participants"), eq(Integer.class), eq("12345"), eq(0)))
                .thenReturn(1);
        when(jdbc.update(contains("INTO driver_sessions"), eq(1L), eq("12345"), eq(0)))
                .thenReturn(1);

        mockMvc.perform(post("/api/drivers/1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionUid\":\"12345\",\"carIndex\":0}"))
                .andExpect(status().isCreated());
    }

    @Test
    void associateSessionReturns404WhenDriverMissing() throws Exception {
        when(jdbc.queryForObject(contains("FROM drivers"), eq(Integer.class), eq(99L)))
                .thenReturn(0);

        mockMvc.perform(post("/api/drivers/99/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionUid\":\"12345\",\"carIndex\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void associateSessionReturns404WhenParticipantMissing() throws Exception {
        when(jdbc.queryForObject(contains("FROM drivers"), eq(Integer.class), eq(1L)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("FROM participants"), eq(Integer.class), eq("12345"), eq(0)))
                .thenReturn(0);

        mockMvc.perform(post("/api/drivers/1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionUid\":\"12345\",\"carIndex\":0}"))
                .andExpect(status().isNotFound());
    }

    // ── Remove session ───────────────────────────────────────────────────

    @Test
    void removeSessionReturns204() throws Exception {
        when(jdbc.update(contains("FROM driver_sessions"), eq(1L), eq("12345")))
                .thenReturn(1);

        mockMvc.perform(delete("/api/drivers/1/sessions/12345"))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeSessionReturns404WhenNotFound() throws Exception {
        when(jdbc.update(contains("FROM driver_sessions"), eq(1L), eq("99999")))
                .thenReturn(0);

        mockMvc.perform(delete("/api/drivers/1/sessions/99999"))
                .andExpect(status().isNotFound());
    }
}
