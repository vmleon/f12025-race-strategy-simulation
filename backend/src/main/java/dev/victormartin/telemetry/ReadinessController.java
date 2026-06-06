package dev.victormartin.telemetry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.victormartin.telemetry.ReadinessCalculator.CompoundReadiness;
import dev.victormartin.telemetry.ReadinessCalculator.SectorRow;

/**
 * Read-only calibration/simulation data-readiness for the Portal System view.
 * Projects clean/bad sector rows from sector_snapshots and computes confidence
 * in {@link ReadinessCalculator}; fitted-state flags come from
 * sector_pace_baselines / calibration_coefficients.
 */
@RestController
@RequestMapping("/api/system/readiness")
public class ReadinessController {

    private final JdbcTemplate jdbc;

    public ReadinessController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TrackOption(int trackId, String trackName, String lastSessionAt) {}

    public record CompoundReadinessDto(int compound, String name, int total, int good,
                                       ReadinessCalculator.Reasons reasons, double confidence,
                                       boolean wearFitted, boolean baselineFitted, boolean degFitted,
                                       List<ReadinessCalculator.SectorReadiness> sectors) {}

    public record ReadinessResponse(int trackId, String trackName, String calibrationLastRanAt,
                                    double overallConfidence, boolean fuelEffectFitted,
                                    List<CompoundReadinessDto> compounds) {}

    // ── per-sector degradation scatter ───────────────────────────────────────
    public record ScatterPoint(int age, long timeMs, boolean used, boolean current) {}
    public record Regression(double slope, double intercept, int n) {}
    public record CompoundScatter(int compound, List<ScatterPoint> points, Regression regression) {}
    public record SectorScatter(int sector, List<CompoundScatter> compounds) {}
    public record ScatterResponse(int trackId, String currentSessionUid, List<SectorScatter> sectors) {}

    @GetMapping("/tracks")
    public List<TrackOption> tracks() {
        return jdbc.queryForList(
                "SELECT s.track_id AS track_id, TO_CHAR(MAX(s.created_at), 'YYYY-MM-DD\"T\"HH24:MI:SS') AS last_at "
                + "FROM sessions s "
                + "WHERE EXISTS (SELECT 1 FROM sector_snapshots ss WHERE ss.session_uid = s.session_uid) "
                + "GROUP BY s.track_id ORDER BY MAX(s.created_at) DESC")
                .stream()
                .map(r -> {
                    int id = ((Number) r.get("TRACK_ID")).intValue();
                    return new TrackOption(id, GameMappings.trackName(id), String.valueOf(r.get("LAST_AT")));
                })
                .toList();
    }

    @GetMapping
    public ReadinessResponse readiness(@RequestParam(value = "trackId", required = false) Integer trackId) {
        int resolved = trackId != null ? trackId : mostRecentTrack();
        if (resolved < 0) {
            return new ReadinessResponse(-1, "—", null, 0.0, false, List.of());
        }

        List<SectorRow> rows = projectRows(resolved);
        List<CompoundReadiness> base = ReadinessCalculator.aggregate(rows);

        Set<Integer> wearCompounds = wearFittedCompounds(resolved);
        Set<Integer> baselineCompounds = baselineFittedCompounds(resolved);
        Set<Integer> degCompounds = degFittedCompounds(resolved);
        boolean fuelFitted = fuelEffectFitted(resolved);
        String lastRan = calibrationLastRanAt(resolved);

        List<CompoundReadinessDto> compounds = new ArrayList<>();
        List<Double> withData = new ArrayList<>();
        for (CompoundReadiness c : base) {
            if (c.total() > 0) withData.add(c.confidence());
            compounds.add(new CompoundReadinessDto(
                    c.compound(), c.name(), c.total(), c.good(), c.reasons(), c.confidence(),
                    wearCompounds.contains(c.compound()),
                    baselineCompounds.contains(c.compound()), degCompounds.contains(c.compound()),
                    c.sectors()));
        }
        double overall = ReadinessCalculator.overallConfidence(withData);
        return new ReadinessResponse(
                resolved, GameMappings.trackName(resolved), lastRan, overall, fuelFitted, compounds);
    }

    /** Per-sector degradation scatter for the PLAYER car: tyre age (X) vs sector time
     * (Y), per dry compound, with the calibration-used / current flags and a fitted
     * line over the used points. Feeds the System-page degradation charts. */
    @GetMapping("/scatter")
    public ScatterResponse scatter(@RequestParam(value = "trackId", required = false) Integer trackId) {
        int resolved = trackId != null ? trackId : mostRecentTrack();
        if (resolved < 0) return new ScatterResponse(-1, null, List.of());

        String currentUid = latestSessionUid(resolved);

        // sector (0/1/2) -> compound -> points. LinkedHashMap keeps sectors ordered.
        Map<Integer, Map<Integer, List<ScatterPoint>>> bySector = new LinkedHashMap<>();
        for (int s = 0; s < 3; s++) bySector.put(s, new LinkedHashMap<>());

        RowCallbackHandler collector = rs -> {
            int sector = rs.getInt("SECTOR");
            int compound = rs.getInt("COMPOUND");
            boolean used = rs.getInt("INVALID") == 0 && rs.getInt("OUTLIER") == 0
                    && rs.getInt("PIT") == 0 && rs.getInt("SC") == 0;
            boolean current = currentUid != null && currentUid.equals(rs.getString("SESSION_UID"));
            bySector.get(sector)
                    .computeIfAbsent(compound, k -> new ArrayList<>())
                    .add(new ScatterPoint(rs.getInt("AGE"), rs.getLong("TIME_MS"), used, current));
        };

        jdbc.query(
                "SELECT ss.sector_number AS sector, ss.tyre_compound_visual AS compound, "
                + "ss.tyre_age_laps AS age, ss.sector_time_ms AS time_ms, "
                + "ss.lap_invalid AS invalid, ss.outlier AS outlier, "
                + "ss.pit_status AS pit, ss.safety_car_status AS sc, ss.session_uid AS session_uid "
                + "FROM sector_snapshots ss "
                + "JOIN sessions s ON s.session_uid = ss.session_uid "
                + "JOIN participants p ON p.session_uid = ss.session_uid AND p.car_index = ss.car_index "
                + "WHERE s.track_id = ? AND p.ai_controlled = 0 "
                + "  AND ss.tyre_compound_visual IN (16,17,18) "
                + "  AND ss.sector_number IN (0,1,2) AND ss.sector_time_ms > 0",
                collector, resolved);

        List<SectorScatter> sectors = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            List<CompoundScatter> comps = new ArrayList<>();
            for (int compound : new int[] {16, 17, 18}) {
                List<ScatterPoint> pts = bySector.get(s).getOrDefault(compound, List.of());
                comps.add(new CompoundScatter(compound, pts, computeRegression(pts)));
            }
            sectors.add(new SectorScatter(s, comps));
        }
        return new ScatterResponse(resolved, currentUid, sectors);
    }

    private String latestSessionUid(int trackId) {
        List<String> uids = jdbc.queryForList(
                "SELECT session_uid FROM sessions WHERE track_id = ? "
                + "ORDER BY created_at DESC FETCH FIRST 1 ROW ONLY",
                String.class, trackId);
        return uids.isEmpty() ? null : uids.get(0);
    }

    /** OLS fit over the calibration-used points only (the filled dots), so the line
     * mirrors what the deg model fits. Null if < 2 used points or zero x-variance. */
    private static Regression computeRegression(List<ScatterPoint> points) {
        List<ScatterPoint> used = points.stream().filter(ScatterPoint::used).toList();
        int n = used.size();
        if (n < 2) return null;
        double sx = 0, sy = 0;
        for (ScatterPoint p : used) { sx += p.age(); sy += p.timeMs(); }
        double mx = sx / n, my = sy / n;
        double sxx = 0, sxy = 0;
        for (ScatterPoint p : used) {
            double dx = p.age() - mx;
            sxx += dx * dx;
            sxy += dx * (p.timeMs() - my);
        }
        if (sxx == 0) return null;
        double slope = sxy / sxx;
        return new Regression(slope, my - slope * mx, n);
    }

    private int mostRecentTrack() {
        List<Integer> ids = jdbc.queryForList(
                "SELECT track_id FROM sessions ORDER BY created_at DESC FETCH FIRST 1 ROW ONLY",
                Integer.class);
        return ids.isEmpty() ? -1 : ids.get(0);
    }

    private List<SectorRow> projectRows(int trackId) {
        return jdbc.query(
                "SELECT ss.tyre_compound_visual AS compound, ss.sector_number AS sector, "
                + "ss.pit_status AS pit, ss.safety_car_status AS sc, ss.lap_invalid AS invalid, "
                + "ss.corner_cutting_warnings AS cut, ss.lap_number AS lap, ss.outlier AS outlier, "
                + "ss.sector_time_ms AS time_ms, "
                + "CASE WHEN ss.front_wing_damage_l > 0 OR ss.front_wing_damage_r > 0 "
                + "  OR ss.rear_wing_damage > 0 OR ss.floor_damage > 0 OR ss.diffuser_damage > 0 "
                + "  OR ss.sidepod_damage > 0 OR ss.engine_damage > 0 OR ss.gearbox_damage > 0 "
                + "  THEN 1 ELSE 0 END AS damaged, s.session_type AS session_type "
                + "FROM sector_snapshots ss JOIN sessions s ON s.session_uid = ss.session_uid "
                + "WHERE s.track_id = ? AND ss.tyre_compound_visual IN (7,8,16,17,18) "
                + "  AND ss.sector_number IN (0,1,2)",
                (rs, i) -> new SectorRow(
                        rs.getInt("COMPOUND"), rs.getInt("SECTOR"), rs.getInt("PIT"),
                        rs.getInt("SC"), rs.getInt("INVALID"), rs.getInt("CUT"),
                        rs.getInt("LAP"), rs.getInt("OUTLIER"),
                        rs.getObject("TIME_MS") == null ? null : rs.getLong("TIME_MS"),
                        rs.getInt("DAMAGED") == 1, rs.getInt("SESSION_TYPE")),
                trackId);
    }

    /** Compounds with a calibrated (non-default) wear-rate — the primary readiness
     * signal: it yields the cliff-based stint cap the simulator depends on. */
    private Set<Integer> wearFittedCompounds(int trackId) {
        Set<Integer> out = new HashSet<>();
        for (String knob : jdbc.queryForList(
                "SELECT DISTINCT knob_name FROM calibration_coefficients "
                + "WHERE track_id = ? AND is_default = 0 AND knob_name LIKE 'tyre_wear_rate_%'",
                String.class, trackId)) {
            switch (knob) {
                case "tyre_wear_rate_soft" -> out.add(16);
                case "tyre_wear_rate_medium" -> out.add(17);
                case "tyre_wear_rate_hard" -> out.add(18);
                default -> { }
            }
        }
        return out;
    }

    private Set<Integer> baselineFittedCompounds(int trackId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT compound FROM sector_pace_baselines WHERE track_id = ?",
                Integer.class, trackId));
    }

    private Set<Integer> degFittedCompounds(int trackId) {
        Set<Integer> out = new HashSet<>();
        for (String knob : jdbc.queryForList(
                "SELECT DISTINCT knob_name FROM calibration_coefficients "
                + "WHERE track_id = ? AND is_default = 0 AND knob_name LIKE 'tyre_deg_%'",
                String.class, trackId)) {
            switch (knob) {
                case "tyre_deg_soft" -> out.add(16);
                case "tyre_deg_medium" -> out.add(17);
                case "tyre_deg_hard" -> out.add(18);
                case "tyre_deg_intermediate" -> out.add(7);
                case "tyre_deg_wet" -> out.add(8);
                default -> { }
            }
        }
        return out;
    }

    private boolean fuelEffectFitted(int trackId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_coefficients "
                + "WHERE track_id = ? AND knob_name = 'fuel_effect' AND is_default = 0",
                Long.class, trackId);
        return n != null && n > 0;
    }

    private String calibrationLastRanAt(int trackId) {
        List<String> ts = jdbc.queryForList(
                "SELECT TO_CHAR(MAX(last_fitted_at), 'YYYY-MM-DD\"T\"HH24:MI:SS') "
                + "FROM sector_pace_baselines WHERE track_id = ?",
                String.class, trackId);
        return ts.isEmpty() ? null : ts.get(0);
    }
}
