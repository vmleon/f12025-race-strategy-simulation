package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DriverRatingController.class)
class DriverRatingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    @Test
    void listReturnsDriverRatings() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(
                        new DriverRatingController.DriverRatingDto("Hamilton", -1, 90),
                        new DriverRatingController.DriverRatingDto("Verstappen", -1, 95)));

        mockMvc.perform(get("/api/driver-ratings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].driverName").value("Hamilton"))
                .andExpect(jsonPath("$[0].skillRating").value(90));
    }

    @Test
    void updateReturnsUpdatedRating() throws Exception {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        mockMvc.perform(put("/api/driver-ratings/Hamilton")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillRating\":85,\"trackId\":-1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverName").value("Hamilton"))
                .andExpect(jsonPath("$.skillRating").value(85))
                .andExpect(jsonPath("$.trackId").value(-1));
    }
}
