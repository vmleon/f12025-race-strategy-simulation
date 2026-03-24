package dev.victormartin.telemetry;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/driver-ratings")
public class DriverRatingController {

    private final JdbcTemplate jdbc;

    public DriverRatingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DriverRatingDto(String driverName, int trackId, int skillRating) {}

    public record UpdateRatingRequest(int skillRating, int trackId) {}

    @GetMapping
    public List<DriverRatingDto> list() {
        return jdbc.query(
                "SELECT driver_name, track_id, skill_rating FROM driver_ratings ORDER BY driver_name, track_id",
                (rs, rowNum) -> new DriverRatingDto(
                        rs.getString("driver_name"),
                        rs.getInt("track_id"),
                        rs.getInt("skill_rating")));
    }

    @PutMapping("/{driverName}")
    public Map<String, Object> update(@PathVariable String driverName, @RequestBody UpdateRatingRequest req) {
        if (req.skillRating() < 0 || req.skillRating() > 100) {
            throw new IllegalArgumentException("skillRating must be between 0 and 100");
        }
        jdbc.update("""
                MERGE INTO driver_ratings dr
                USING (SELECT ? driver_name, ? track_id FROM dual) v
                ON (dr.driver_name = v.driver_name AND dr.track_id = v.track_id)
                WHEN NOT MATCHED THEN INSERT (driver_name, track_id, skill_rating)
                    VALUES (?, ?, ?)
                WHEN MATCHED THEN UPDATE SET skill_rating = ?, updated_at = SYSTIMESTAMP
                """,
                driverName, req.trackId(),
                driverName, req.trackId(), req.skillRating(),
                req.skillRating());
        return Map.of("driverName", driverName, "trackId", req.trackId(), "skillRating", req.skillRating());
    }
}
