# Spring Boot vs Plain Java — Architecture Analysis

## Context

The project is a POC/TFG with 3 future components that scale differently:

1. **Ingestion** — Real-time UDP listener → parse → write to Oracle AI Database 26ai (~60 rows/lap, continuous)
2. **Calibration** — Offline batch regression after each session (infrequent, CPU-bound)
3. **Monte Carlo** — On-demand simulation, 1K-10K iterations (burst, CPU-bound)

Current state: ~350 lines of plain Java, only header parsing done. 0/7 DB tables, no payload parsing yet.

---

## Chosen: Plain Java + Lightweight Libraries

**What it looks like:**

- Keep plain Java structure
- Add **Oracle UCP** ([Oracle Corporation, 2025](10-REFERENCES.md#oracle-ucp)) (Universal Connection Pool) for connection pooling
- Add **Oracle JDBC driver** (required regardless)
- Use raw JDBC with prepared statements (the 7 tables are well-defined, ORM is overkill)
- 3 independent Gradle submodules sharing a `common` module:
  ```
  common/       — PacketHeader, PacketType, DB schema constants, Oracle UCP connection factory
  ingestion/    — UDP server + packet parsers + DB writers
  calibration/  — Reads sector_snapshots, fits coefficients, writes calibration_coefficients
  simulation/   — Loads coefficients, runs Monte Carlo, outputs results
  ```
- Each submodule produces its own runnable JAR
- Config via `.properties` files (already working)

**Pros:**

- Minimal new dependencies (Oracle UCP + Oracle JDBC)
- Components are truly independent (separate JARs, separate processes)
- Shared code in `common` avoids duplication without a framework
- No framework overhead, instant startup
- Natural Gradle multi-module = clean dependency boundaries
- Scales to "real" deployment: each JAR can run on different machines/containers
- Easy to add Spring Boot to any single module later if needed

**Cons:**

- Manual dependency wiring (but for a POC with 3-5 classes per module, this is trivial)
- No auto-config for datasource (but `PoolDataSource` setup is ~5 lines)
- No built-in HTTP (add Javalin or similar later if needed, ~1 more dependency)

**Effort:** Low-Medium. Restructure to multi-module, add Oracle UCP + JDBC dependencies.

### Alternatives considered

**Plain Java with JDBC only** would keep the current structure and add a thin `DbWriter` with raw SQL — zero new dependencies and the fastest path to ship, but it lacks connection pooling (manual or via Oracle UCP) and offers no built-in observability. **Spring Boot** would provide turnkey Oracle/JPA integration, automatic dependency injection, and a trivial path to HTTP endpoints, but it is heavy for a UDP-only POC: 2–4s startup overhead ([VMware, 2025](10-REFERENCES.md#spring-boot)), 30+ transitive dependencies, and no native UDP abstractions — you'd still write raw `DatagramSocket` code inside a Spring bean. Running three separate Spring Boot apps would triple the boilerplate and startup cost.

---

## Chosen: Raw JDBC (Prepared Statements)

The schema has 7 tables with well-defined, stable structures. Raw JDBC with prepared statements is the right fit for this workload.

```java
// Example: insert sector snapshot (~5 lines of setup, explicit SQL)
var sql = "INSERT INTO sector_snapshots (session_uid, car_index, lap_number, sector_number, sector_time_ms, ...) VALUES (?, ?, ?, ?, ?, ...)";
try (var ps = conn.prepareStatement(sql)) {
    ps.setLong(1, sessionUid);
    ps.setInt(2, carIndex);
    // ... set remaining fields
    ps.executeUpdate();
}
```

**Why Raw JDBC wins for this project:**

- **Write-heavy, read-light workload** — Ingestion is 99% inserts. JDBC prepared statements are the fastest path for bulk inserts. No ORM overhead per row
- **Flat, denormalized tables** — `sector_snapshots` has ~40 columns but no complex relationships (no `@OneToMany` cascades, no lazy loading). ORM adds nothing here
- **Batch inserts** — JDBC `addBatch()` / `executeBatch()` gives direct control over batching 60 rows/lap. Hibernate batching requires careful config (`hibernate.jdbc.batch_size`, flush/clear cycles)
- **Calibration queries are analytical** — `SELECT AVG(sector_time_ms) ... GROUP BY compound, tyre_age` — these are aggregate queries. ORM entity mapping is wasted when reading aggregates, not objects
- **Oracle-specific features** — If you ever need Oracle hints, bulk `INSERT ALL`, or PL/SQL calls, raw JDBC gives direct access. ORM abstracts these away
- **No mapping boilerplate** — 7 entity classes + 7 repository interfaces + JPA annotations = ~300-400 lines of code that adds no business value for a POC

**Cons:**

- Column index errors in `ps.setXxx(n, ...)` — mitigated by using named constants or a thin helper
- No automatic schema generation (but you want to control your Oracle schema anyway)
- SQL strings in Java code (mitigated by keeping them as `static final` constants)

To avoid raw column-index errors, use a thin record ([Oracle Corporation, 2024](10-REFERENCES.md#java-se-23)) + helper pattern:

```java
record SectorSnapshot(long sessionUid, int carIndex, int lapNumber, int sectorNumber, ...) {
    void insertInto(Connection conn) throws SQLException {
        // SQL + ps.set* calls here, encapsulated per table
    }
}
```

One record per table, SQL co-located with the data. No framework, full type safety, ~20 lines per table.

### Alternative considered: JPA/Hibernate

JPA/Hibernate would help if the project had complex entity relationships with cascading saves, dynamic criteria queries, or multiple developers needing compile-time-safe data access — none of which apply here (flat tables, fixed analytical queries, solo developer). In this workload it actively hurts: Hibernate's first-level cache would need manual `flush()` + `clear()` cycles at 60 inserts/lap to avoid memory bloat, dirty checking adds overhead to pure inserts, and the Oracle dialect sometimes generates suboptimal batch SQL. The result would be ~400 lines of entity/repository boilerplate replacing ~50 lines of SQL, with lower insert throughput and less control over batching.
