# Plain Java vs Spring Boot for UDP Ingestion

## The Question

The telemetry ingestion component is a real-time UDP listener that receives F1 2025 game packets at ~80-100 packets/second, parses binary headers, and will eventually write snapshots to an Oracle AI Database 26ai. Should this be built with Spring Boot or plain Java?

## Three Options Evaluated

### Option A: Plain Java (add JDBC only)

Keep the raw `DatagramSocket` loop. Add Oracle JDBC driver and a thin `DbWriter` class with prepared statements. Calibration and simulation are separate `main()` classes in the same Gradle module.

- Zero new dependencies beyond Oracle JDBC
- Full control over UDP socket lifecycle
- Fastest to ship

### Option B: Spring Boot

Spring Boot app with `spring-boot-starter-data-jpa` or `spring-boot-starter-jdbc`. UDP server as a `@Component` with `@PostConstruct` lifecycle or a `CommandLineRunner`.

- Oracle/JPA integration is turnkey
- Dependency injection is automatic
- Adding an HTTP API later is trivial

### Option C: Plain Java + Lightweight Libraries (chosen)

Keep plain Java structure. Add Oracle UCP (Universal Connection Pool) for connection pooling and Oracle JDBC driver. Use raw JDBC with prepared statements. Split into Gradle submodules sharing a `common` module.

## Why Spring Boot Lost

**Spring Boot assumes HTTP.** The ingestion component has zero HTTP endpoints. Spring Boot's entire architecture — servlet container, DispatcherServlet, auto-configuration — is built around the request-response HTTP model. The UDP server is a blocking socket loop that runs forever. Fitting this into Spring means fighting the framework rather than using it.

**Spring doesn't provide UDP abstractions.** You'd still write raw `DatagramSocket` code inside a Spring bean. Spring Integration has UDP channel adapters, but that's adding another layer of framework complexity for a problem already solved in ~20 lines of plain Java.

**Dependency overhead.** Spring Boot pulls in 30-50 transitive dependencies. The current server needs zero dependencies beyond the JDK. Adding Oracle UCP and Oracle JDBC (required regardless) brings the total to 2.

**Startup cost.** Spring Boot takes 2-4 seconds to initialize (scanning, context creation, auto-configuration). Plain Java starts instantly. For a service that should be ready to receive packets the moment the game starts, this matters.

**3 separate Spring Boot apps = 3x the boilerplate.** The project has three independent components (ingestion, calibration, simulation). As separate Spring Boot apps, each carries the full framework overhead. As plain Java with shared common code, they're lightweight independent JARs.

## When Spring Boot Would Make Sense

- If the simulation component gets an HTTP API for triggering runs or viewing results
- If multi-user access requires Spring Security
- If the project moves past PoC and needs production observability (Actuator, metrics)

At that point, add Spring Boot to the specific module that needs it, not globally. The telemetry ingestion will likely never need HTTP.

## Key Takeaway

Frameworks solve framework-shaped problems. A UDP socket loop that reads binary packets and writes to a database is not a framework-shaped problem. The simplest tool that works is the right tool.
