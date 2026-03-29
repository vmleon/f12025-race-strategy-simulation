# Spring Boot vs Plain Java — Architecture Analysis

## Context

The project is a POC/TFG with 3 future components that scale differently:
1. **Ingestion** — Real-time UDP listener → parse → write to Oracle AI Database 26ai (~60 rows/lap, continuous)
2. **Calibration** — Offline batch regression after each session (infrequent, CPU-bound)
3. **Monte Carlo** — On-demand simulation, 1K-10K iterations (burst, CPU-bound)

Current state: ~350 lines of plain Java, only header parsing done. 0/7 DB tables, no payload parsing yet.

---

## Option A: Stay Plain Java (add JDBC only)

**What it looks like:**
- Keep current UDP server as-is
- Add Oracle JDBC driver + a thin `DbWriter` class with raw SQL / prepared statements
- Calibration = separate `main()` class that reads DB, runs regression, writes coefficients
- Monte Carlo = separate `main()` class that loads coefficients, runs simulation
- 3 entry points in the same Gradle module, run independently via `./gradlew runIngestion`, `runCalibration`, `runSimulation`
- Config stays in `.properties` files

**Pros:**
- Zero new dependencies beyond Oracle JDBC driver
- No framework learning curve, no magic
- Fastest to ship the POC
- Full control over UDP socket lifecycle (no framework fighting you)
- Each component is just a `main()` — deploy as separate JARs or processes trivially

**Cons:**
- Manual connection pooling (or add Oracle UCP, ~1 dependency)
- Manual wiring of dependencies (constructor injection by hand)
- No built-in health checks, metrics, or HTTP endpoints if you ever want observability
- If the project grows past POC, refactoring to a framework later costs more

**Effort:** Low. Already 90% there structurally.

---

## Option B: Spring Boot

**What it looks like:**
- Spring Boot app with `spring-boot-starter-data-jpa` or `spring-boot-starter-jdbc`
- Oracle connectivity via `spring-boot-starter-data-jpa` + Oracle JDBC driver
- UDP server as a `@Component` with `@PostConstruct` lifecycle (or a `CommandLineRunner`)
- `@Service` classes for ingestion logic, calibration, simulation
- `application.yml` for all config
- Could be 1 monolith or 3 separate Spring Boot apps

**Pros:**
- Oracle/JPA integration is turnkey (`spring.datasource.*` config, entity mapping, repositories)
- Dependency injection is automatic (`@Autowired`, constructor injection)
- If you later want an HTTP API (trigger simulation, check status), it's trivial to add
- Spring Profiles for dev/prod config switching
- Good testing support (`@SpringBootTest`, `@DataJpaTest`)
- Well-documented, huge ecosystem

**Cons:**
- **Heavy for a UDP-only POC** — Spring Boot assumes HTTP; ingestion has zero HTTP
- Startup overhead (~2-4s vs instant for plain Java)
- Spring context initialization adds complexity for a simple UDP loop
- **UDP socket management** — Spring doesn't provide UDP abstractions (Spring Integration does, but that's another layer). You'd still write raw `DatagramSocket` code inside a Spring bean
- JPA entity mapping for 7 tables is boilerplate you don't need if raw SQL works
- ~30+ transitive dependencies for something that currently needs 0
- Risk of over-engineering a POC
- **3 separate Spring Boot apps** = 3× the boilerplate, 3× the startup cost

**Effort:** Medium-High. Significant restructuring, new build config, entity classes, repository interfaces.

---

## Option C: Plain Java + Lightweight Libraries (Recommended)

**What it looks like:**
- Keep plain Java structure
- Add **Oracle UCP** (Universal Connection Pool) for connection pooling
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

---

## Comparison Matrix

| Criteria | Plain Java | Spring Boot | Plain Java + Libs |
|----------|-----------|-------------|-------------------|
| Time to first DB write | **Fast** | Slow (setup) | **Fast** |
| Oracle connectivity | Manual JDBC | Auto-config | Oracle UCP + JDBC |
| UDP server fit | **Native** | Awkward | **Native** |
| Component independence | 3 main() classes | 3 apps or profiles | **3 Gradle modules** |
| Dependency count | +1 (JDBC) | +30-50 | +2 (JDBC + UCP) |
| Startup time | **Instant** | 2-4s | **Instant** |
| Future HTTP API | Add later | **Built-in** | Add Javalin later |
| DI complexity | Manual (fine for POC) | **Auto** | Manual (fine for POC) |
| Matches POC philosophy | **Yes** | No | **Yes** |
| Scales past POC | Needs refactor | **Yes** | Yes |

---

## ORM (JPA/Hibernate) vs Raw JDBC — Deeper Comparison

The schema has 7 tables with well-defined, stable structures. Here's how each approach plays out:

### Raw JDBC (Prepared Statements)

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

**Pros for this project:**
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

### JPA/Hibernate

**Where it would help:**
- If you had complex entity relationships with cascading saves (you don't — tables are flat)
- If you needed query-by-example or dynamic criteria queries (queries are fixed and analytical)
- If multiple developers needed a shared data access layer with compile-time safety (solo developer)
- If you wanted auto-generated schema migrations (Oracle DBA schema is already designed)

**Where it actively hurts:**
- **First-level cache** — Hibernate caches every entity in the persistence context. At 60 inserts/lap, you'd need to manually `flush()` + `clear()` to avoid memory bloat
- **Dirty checking overhead** — Hibernate checks every managed entity for changes on flush. Pure inserts don't benefit from this
- **N+1 query risk** — Not applicable since you're mostly writing, but a footgun waiting for the calibration reads
- **Oracle dialect quirks** — Hibernate's Oracle dialect sometimes generates suboptimal SQL for batch operations
- **Startup cost** — Hibernate scans entities, validates schema, builds metamodel — adds 1-2s to startup

### ORM vs JDBC Verdict

| Aspect | Raw JDBC | JPA/Hibernate |
|--------|----------|---------------|
| Insert throughput | **Fastest** (direct) | Slower (entity lifecycle) |
| Batch control | **Full** (`addBatch`) | Needs careful tuning |
| Aggregate reads (calibration) | **Natural** (SQL) | Awkward (projections/DTOs) |
| Boilerplate | ~50 lines of SQL | ~400 lines of entities/repos |
| Type safety | Low (column indices) | High (entity fields) |
| Schema control | **Full** | Shared with Hibernate |
| Learning curve | None | Medium (JPA gotchas) |

**For this project: Raw JDBC wins clearly.** The workload is write-heavy inserts into flat tables with analytical reads — the exact opposite of what ORM is designed for (complex object graphs with relationships). Save the ORM for if/when you build a web UI that needs CRUD operations on individual entities.

### Practical tip

To avoid raw column-index errors, use a thin record + helper pattern:

```java
record SectorSnapshot(long sessionUid, int carIndex, int lapNumber, int sectorNumber, ...) {
    void insertInto(Connection conn) throws SQLException {
        // SQL + ps.set* calls here, encapsulated per table
    }
}
```

One record per table, SQL co-located with the data. No framework, full type safety, ~20 lines per table.

---

## Recommendation

**Option C (Plain Java + Lightweight Libraries)** is the sweet spot for this project:

1. Spring Boot solves problems you don't have yet (HTTP, auto-config, DI for large graphs)
2. The UDP ingestion actively fights Spring's assumptions (no servlet, no HTTP, custom socket lifecycle)
3. The 3-module Gradle split gives you real independence without framework overhead
4. Raw JDBC with 7 well-defined tables is simpler than JPA entity mapping
5. If the POC succeeds and needs to become production-grade, adding Spring Boot to the simulation module (which might want an HTTP trigger API) is a contained change

**When Spring Boot WOULD make sense:**
- If you add an HTTP REST API for triggering simulations or viewing results
- If you need Spring Security for multi-user access
- If the project moves past POC and needs production observability (actuator, metrics)
- At that point, add it to the specific module that needs it, not globally
