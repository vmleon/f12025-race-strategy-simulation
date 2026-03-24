package dev.victormartin.telemetry;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calibration")
public class CalibrationController {

    private final JdbcTemplate jdbc;
    private final CalibrationService calibrationService;

    public CalibrationController(JdbcTemplate jdbc, CalibrationService calibrationService) {
        this.jdbc = jdbc;
        this.calibrationService = calibrationService;
    }

    public record CalibrationStatusDto(String knobName, String calibrationRegime,
                                       Integer sectorNumber, double value,
                                       double confidence, boolean isDefault,
                                       int sessionCount, int dataPointCount,
                                       String trainedAt) {}

    @GetMapping("/status")
    public List<CalibrationStatusDto> status(@RequestParam(required = false) Integer trackId) {
        if (trackId != null) {
            return jdbc.query("""
                    SELECT knob_name, calibration_regime, sector_number, value,
                           confidence, is_default, session_count, data_point_count,
                           TO_CHAR(trained_at, 'YYYY-MM-DD"T"HH24:MI:SS') trained_at
                    FROM (
                        SELECT knob_name, calibration_regime, sector_number, value,
                               confidence, is_default, session_count, data_point_count, trained_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY knob_name, calibration_regime, NVL(sector_number, -1)
                                   ORDER BY trained_at DESC
                               ) rn
                        FROM calibration_coefficients
                        WHERE track_id = ?
                    )
                    WHERE rn = 1
                    ORDER BY knob_name, calibration_regime, sector_number
                    """,
                    (rs, rowNum) -> {
                        int sector = rs.getInt("sector_number");
                        return new CalibrationStatusDto(
                                rs.getString("knob_name"),
                                rs.getString("calibration_regime"),
                                rs.wasNull() ? null : sector,
                                rs.getDouble("value"),
                                rs.getDouble("confidence"),
                                rs.getInt("is_default") == 1,
                                rs.getInt("session_count"),
                                rs.getInt("data_point_count"),
                                rs.getString("trained_at"));
                    },
                    trackId);
        }
        // Without trackId: return summary across all tracks
        return jdbc.query("""
                SELECT knob_name, calibration_regime, sector_number, value,
                       confidence, is_default, session_count, data_point_count,
                       TO_CHAR(trained_at, 'YYYY-MM-DD"T"HH24:MI:SS') trained_at
                FROM (
                    SELECT knob_name, calibration_regime, sector_number, value,
                           confidence, is_default, session_count, data_point_count, trained_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY knob_name, calibration_regime, NVL(sector_number, -1)
                               ORDER BY trained_at DESC
                           ) rn
                    FROM calibration_coefficients
                )
                WHERE rn = 1
                ORDER BY knob_name, calibration_regime, sector_number
                """,
                (rs, rowNum) -> {
                    int sector = rs.getInt("sector_number");
                    return new CalibrationStatusDto(
                            rs.getString("knob_name"),
                            rs.getString("calibration_regime"),
                            rs.wasNull() ? null : sector,
                            rs.getDouble("value"),
                            rs.getDouble("confidence"),
                            rs.getInt("is_default") == 1,
                            rs.getInt("session_count"),
                            rs.getInt("data_point_count"),
                            rs.getString("trained_at"));
                });
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> run(@RequestParam int trackId) {
        if (calibrationService.isRunning()) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "Calibration already running",
                                 "trackId", String.valueOf(trackId)));
        }
        boolean started = calibrationService.triggerCalibration(trackId);
        if (!started) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "Calibration already running",
                                 "trackId", String.valueOf(trackId)));
        }
        return ResponseEntity.accepted()
                .body(Map.of("status", "started",
                             "trackId", String.valueOf(trackId)));
    }
}
