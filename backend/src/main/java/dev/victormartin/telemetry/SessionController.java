package dev.victormartin.telemetry;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionStateHolder sessionStateHolder;
    private final RaceWebSocketHandler raceWebSocketHandler;
    private final JdbcTemplate jdbc;

    private static final RowMapper<SessionDto> SESSION_ROW_MAPPER = (rs, rowNum) -> {
        long did = rs.getLong("driver_id");
        return new SessionDto(
                rs.getString("session_uid"), rs.getInt("track_id"),
                rs.getString("session_type"), rs.getInt("total_laps"),
                rs.getInt("ai_difficulty"), rs.getString("created_at"),
                rs.wasNull() ? null : did, rs.getString("driver_name"));
    };

    public SessionController(SessionStateHolder sessionStateHolder,
                             RaceWebSocketHandler raceWebSocketHandler,
                             JdbcTemplate jdbc) {
        this.sessionStateHolder = sessionStateHolder;
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.jdbc = jdbc;
    }

    public record ActiveSessionDto(String sessionUid, String trackName, String sessionType) {}

    @GetMapping("/active")
    public List<ActiveSessionDto> activeSessions() {
        return sessionStateHolder.getActiveSessions().stream()
                .map(s -> {
                    String sessionType = "";
                    try {
                        int typeId = jdbc.queryForObject(
                                "SELECT session_type FROM sessions WHERE session_uid = ?",
                                Integer.class, s.sessionUid());
                        sessionType = GameMappings.sessionTypeName(typeId);
                    } catch (Exception ignored) {}
                    return new ActiveSessionDto(
                            s.sessionUid(),
                            GameMappings.trackName(s.trackId()),
                            sessionType);
                })
                .toList();
    }

    @GetMapping("/active/state")
    public ResponseEntity<String> activeState() {
        if (sessionStateHolder.getActiveSessions().isEmpty()) return ResponseEntity.notFound().build();
        String state = raceWebSocketHandler.getLatestState();
        if (state == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(state);
    }

    // ── Database-backed endpoints ────────────────────────────────────────

    public record SessionDto(String sessionUid, int trackId, String sessionType,
                             int totalLaps, int aiDifficulty, String createdAt,
                             Long driverId, String driverName) {}

    public record SessionDetailDto(String sessionUid, int trackId, String sessionType,
                                   int totalLaps, int aiDifficulty, String createdAt,
                                   List<ParticipantDto> participants,
                                   Long driverId, String driverName, Integer assignedCarIndex) {}

    public record ParticipantDto(int carIndex, String driverName, int teamId, boolean aiControlled) {}

    public record SectorSnapshotDto(int carIndex, int lapNumber, int sectorNumber,
                                    double sectorTimeMs, int carPosition, String tyreCompoundActual,
                                    int tyreCompoundVisual, int tyreAgeLaps, int weather) {}

    @GetMapping
    public List<SessionDto> listSessions(
            @RequestParam(required = false) Integer trackId,
            @RequestParam(defaultValue = "20") int limit) {
        String trackFilter = trackId != null ? "\n    WHERE s.track_id = ?" : "";
        String sql = """
                SELECT s.session_uid, s.track_id, s.session_type, s.total_laps, s.ai_difficulty,
                       TO_CHAR(s.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') created_at,
                       d.driver_id, d.name driver_name
                FROM sessions s
                LEFT JOIN driver_sessions ds ON ds.session_uid = s.session_uid
                LEFT JOIN drivers d ON d.driver_id = ds.driver_id%s
                ORDER BY s.created_at DESC
                FETCH FIRST ? ROWS ONLY
                """.formatted(trackFilter);

        if (trackId != null) {
            return jdbc.query(sql, SESSION_ROW_MAPPER, trackId, limit);
        }
        return jdbc.query(sql, SESSION_ROW_MAPPER, limit);
    }

    @GetMapping("/{sessionUid}")
    public ResponseEntity<SessionDetailDto> getSession(@PathVariable String sessionUid) {
        var sessions = jdbc.query("""
                SELECT s.session_uid, s.track_id, s.session_type, s.total_laps, s.ai_difficulty,
                       TO_CHAR(s.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') created_at,
                       d.driver_id, d.name driver_name
                FROM sessions s
                LEFT JOIN driver_sessions ds ON ds.session_uid = s.session_uid
                LEFT JOIN drivers d ON d.driver_id = ds.driver_id
                WHERE s.session_uid = ?
                """,
                SESSION_ROW_MAPPER,
                sessionUid);
        if (sessions.isEmpty()) return ResponseEntity.notFound().build();

        var session = sessions.getFirst();
        var participants = jdbc.query("""
                SELECT car_index, driver_name, team_id, ai_controlled
                FROM participants
                WHERE session_uid = ?
                ORDER BY car_index
                """,
                (rs, rowNum) -> new ParticipantDto(
                        rs.getInt("car_index"), rs.getString("driver_name"),
                        rs.getInt("team_id"), rs.getInt("ai_controlled") == 1),
                sessionUid);

        return ResponseEntity.ok(new SessionDetailDto(
                session.sessionUid(), session.trackId(), session.sessionType(),
                session.totalLaps(), session.aiDifficulty(), session.createdAt(),
                participants, session.driverId(), session.driverName(),
                session.driverId() != null ? jdbc.queryForObject(
                        "SELECT car_index FROM driver_sessions WHERE driver_id = ? AND session_uid = ?",
                        Integer.class, session.driverId(), sessionUid) : null));
    }

    @GetMapping("/{sessionUid}/sectors")
    public List<SectorSnapshotDto> getSectors(
            @PathVariable String sessionUid,
            @RequestParam(required = false) Integer carIndex,
            @RequestParam(required = false) Integer lap) {
        var sql = new StringBuilder("""
                SELECT car_index, lap_number, sector_number, sector_time_ms,
                       car_position, tyre_compound_actual, tyre_compound_visual,
                       tyre_age_laps, weather
                FROM sector_snapshots
                WHERE session_uid = ?
                """);
        var params = new java.util.ArrayList<Object>();
        params.add(sessionUid);

        if (carIndex != null) {
            sql.append(" AND car_index = ?");
            params.add(carIndex);
        }
        if (lap != null) {
            sql.append(" AND lap_number = ?");
            params.add(lap);
        }
        sql.append(" ORDER BY lap_number, sector_number, car_index");

        return jdbc.query(sql.toString(),
                (rs, rowNum) -> new SectorSnapshotDto(
                        rs.getInt("car_index"), rs.getInt("lap_number"),
                        rs.getInt("sector_number"), rs.getDouble("sector_time_ms"),
                        rs.getInt("car_position"), rs.getString("tyre_compound_actual"),
                        rs.getInt("tyre_compound_visual"),
                        rs.getInt("tyre_age_laps"), rs.getInt("weather")),
                params.toArray());
    }
}
