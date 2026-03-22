# Raw JDBC vs ORM (JPA/Hibernate)

## The Question

The database has 7 tables with well-defined, stable structures. Should data access use JPA/Hibernate (the standard Java ORM) or raw JDBC with prepared statements?

## Why Raw JDBC Wins for This Project

### Write-heavy, read-light workload

Ingestion is 99% inserts. JDBC prepared statements are the fastest path for bulk inserts — no ORM overhead per row, no entity lifecycle management, no persistence context tracking.

The core table (`sector_snapshots`) receives ~60 rows per lap. With `addBatch()` / `executeBatch()`, JDBC gives direct control over batching. Hibernate batching requires careful configuration (`hibernate.jdbc.batch_size`, manual `flush()` + `clear()` cycles to avoid memory bloat from the first-level cache).

### Flat, denormalized tables

`sector_snapshots` has ~60 columns but no complex relationships. There are no `@OneToMany` cascades, no lazy loading scenarios, no entity graphs to navigate. ORM exists to manage object graphs with relationships — when tables are flat, it adds mapping boilerplate with no business value.

### Analytical reads for calibration

Calibration queries are aggregates: `SELECT AVG(sector_time_ms) ... GROUP BY compound, tyre_age`. ORM entity mapping is wasted when reading aggregates, not objects. You'd end up writing JPQL that looks like SQL anyway, or using projections/DTOs that bypass entity mapping entirely.

### Oracle-specific features

If the project ever needs Oracle hints, bulk `INSERT ALL`, or PL/SQL calls, raw JDBC gives direct access. ORM abstracts these away, and fighting the abstraction to use database-specific features defeats the purpose.

## Where ORM Would Actively Hurt

**First-level cache:** Hibernate caches every managed entity in the persistence context. At 60 inserts per lap with no reads, the cache grows with zero benefit. Manual `flush()` + `clear()` is required to avoid memory pressure.

**Dirty checking:** Hibernate checks every managed entity for changes on flush. For pure inserts, this is overhead with no purpose.

**Startup cost:** Hibernate scans entities, validates the schema against mappings, and builds the metamodel. This adds 1-2 seconds to startup for functionality that isn't used.

**Mapping boilerplate:** 7 entity classes + 7 repository interfaces + JPA annotations = ~300-400 lines of code that provides no business value for a PoC with flat tables.

## The Practical Alternative

A thin record + helper pattern gives type safety without a framework:

```java
record SectorSnapshot(long sessionUid, int carIndex, int lapNumber, int sectorNumber, ...) {
    void insertInto(Connection conn) throws SQLException {
        // SQL + ps.set* calls here, encapsulated per table
    }
}
```

One record per table, SQL co-located with the data. No framework, full type safety, ~20 lines per table.

## Key Takeaway

ORM shines for CRUD applications with complex entity relationships, lazy loading needs, and read-heavy workloads. This project is the opposite: write-heavy inserts into flat tables with analytical aggregate reads. The right tool for the job is the simpler one.
