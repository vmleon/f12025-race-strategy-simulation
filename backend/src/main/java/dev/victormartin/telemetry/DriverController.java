package dev.victormartin.telemetry;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final JdbcTemplate jdbc;

    public DriverController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public record DriverDto(long driverId, String name, String email, String createdAt,
                                int sessionCount) {}

    public record DriverDetailDto(long driverId, String name, String email, String createdAt,
                                  List<DriverSessionDto> sessions) {}

    public record DriverSessionDto(String sessionUid, int carIndex, int trackId,
                                   String sessionType, String createdAt) {}

    public record CreateDriverRequest(String name, String email) {}

    public record UpdateDriverRequest(String name, String email) {}

    public record AssociateSessionRequest(String sessionUid, int carIndex) {}

    // ── CRUD ─────────────────────────────────────────────────────────────

    @GetMapping
    public List<DriverDto> list() {
        return jdbc.query("""
                SELECT d.driver_id, d.name, d.email,
                       TO_CHAR(d.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') created_at,
                       (SELECT COUNT(*) FROM driver_sessions ds WHERE ds.driver_id = d.driver_id) session_count
                FROM drivers d
                ORDER BY d.name
                """,
                (rs, rowNum) -> new DriverDto(
                        rs.getLong("driver_id"), rs.getString("name"),
                        rs.getString("email"), rs.getString("created_at"),
                        rs.getInt("session_count")));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DriverDetailDto> get(@PathVariable long id) {
        var drivers = jdbc.query("""
                SELECT driver_id, name, email,
                       TO_CHAR(created_at, 'YYYY-MM-DD"T"HH24:MI:SS') created_at,
                       (SELECT COUNT(*) FROM driver_sessions ds WHERE ds.driver_id = d.driver_id) session_count
                FROM drivers d WHERE driver_id = ?
                """,
                (rs, rowNum) -> new DriverDto(
                        rs.getLong("driver_id"), rs.getString("name"),
                        rs.getString("email"), rs.getString("created_at"),
                        rs.getInt("session_count")),
                id);
        if (drivers.isEmpty()) return ResponseEntity.notFound().build();

        var driver = drivers.getFirst();
        var sessions = jdbc.query("""
                SELECT ds.session_uid, ds.car_index, s.track_id, s.session_type,
                       TO_CHAR(s.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') created_at
                FROM driver_sessions ds
                JOIN sessions s ON s.session_uid = ds.session_uid
                WHERE ds.driver_id = ?
                ORDER BY s.created_at DESC
                """,
                (rs, rowNum) -> new DriverSessionDto(
                        rs.getString("session_uid"), rs.getInt("car_index"),
                        rs.getInt("track_id"), rs.getString("session_type"),
                        rs.getString("created_at")),
                id);

        return ResponseEntity.ok(new DriverDetailDto(
                driver.driverId(), driver.name(), driver.email(),
                driver.createdAt(), sessions));
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody CreateDriverRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        try {
            var keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO drivers (name, email) VALUES (?, ?)",
                        new String[]{"driver_id"});
                ps.setString(1, req.name().trim());
                ps.setString(2, req.email());
                return ps;
            }, keyHolder);
            long driverId = keyHolder.getKey().longValue();
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    Map.of("driverId", driverId, "name", req.name().trim()));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A driver with this name already exists"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable long id, @RequestBody UpdateDriverRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        try {
            int rows = jdbc.update("UPDATE drivers SET name = ?, email = ? WHERE driver_id = ?",
                    req.name().trim(), req.email(), id);
            if (rows == 0) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("driverId", id, "name", req.name().trim()));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A driver with this name already exists"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        jdbc.update("DELETE FROM driver_sessions WHERE driver_id = ?", id);
        int rows = jdbc.update("DELETE FROM drivers WHERE driver_id = ?", id);
        if (rows == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.noContent().build();
    }

    // ── Session associations ─────────────────────────────────────────────

    @PostMapping("/{id}/sessions")
    public ResponseEntity<Object> associateSession(@PathVariable long id,
                                                   @RequestBody AssociateSessionRequest req) {
        // Verify driver exists
        var driverCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM drivers WHERE driver_id = ?", Integer.class, id);
        if (driverCount == 0) return ResponseEntity.notFound().build();

        // Verify participant exists
        var partCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM participants WHERE session_uid = ? AND car_index = ?",
                Integer.class, req.sessionUid(), req.carIndex());
        if (partCount == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session/car index not found in participants"));
        }

        try {
            jdbc.update("INSERT INTO driver_sessions (driver_id, session_uid, car_index) VALUES (?, ?, ?)",
                    id, req.sessionUid(), req.carIndex());
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    Map.of("driverId", id, "sessionUid", req.sessionUid(), "carIndex", req.carIndex()));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This session is already associated with this driver"));
        }
    }

    @DeleteMapping("/{id}/sessions/{sessionUid}")
    public ResponseEntity<Void> removeSession(@PathVariable long id, @PathVariable String sessionUid) {
        int rows = jdbc.update(
                "DELETE FROM driver_sessions WHERE driver_id = ? AND session_uid = ?", id, sessionUid);
        if (rows == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.noContent().build();
    }
}
