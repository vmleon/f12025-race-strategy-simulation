package dev.victormartin.telemetry.simulation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads tyre set inventory from the database for strategy feasibility checks.
 */
@Repository
public class TyreSetRepository {

    private final JdbcTemplate jdbc;

    public TyreSetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns available (not fitted, not fully worn) tyre sets for a car in a session.
     */
    public List<TyreSet> availableSets(long sessionUid, int carIndex) {
        return jdbc.query("""
                SELECT set_index, tyre_compound_actual, wear, usable_life
                FROM tyre_sets
                WHERE session_uid = ? AND car_index = ?
                  AND available = 1 AND fitted = 0
                ORDER BY set_index
                """,
                (rs, rowNum) -> new TyreSet(
                        rs.getInt("set_index"),
                        rs.getInt("tyre_compound_actual"),
                        rs.getDouble("wear"),
                        rs.getInt("usable_life")),
                sessionUid, carIndex);
    }

    public record TyreSet(int setIndex, int compound, double wear, int usableLife) {}
}
