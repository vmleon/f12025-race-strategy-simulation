# TCP Push Architecture for Telemetry-to-Backend Communication

## The Question

The telemetry server (plain Java, UDP) and backend (Spring Boot, HTTP/WS) are separate processes. How should live race data and session lifecycle events flow from telemetry to backend?

## Five Options Evaluated

### Option A: REST (POST from telemetry to backend)

Telemetry makes HTTP POST calls to backend endpoints with race state and lifecycle events.

- Adds an HTTP client library to the telemetry server (or verbose `HttpURLConnection` code)
- Request/response overhead per message — connection setup, headers, parsing, response handling
- At 1Hz, that's 1 HTTP request/second — workable but wasteful for a persistent data stream
- Backend unavailability causes immediate failures that telemetry must handle

### Option B: WebSocket (telemetry connects to backend WS endpoint)

Telemetry opens a WebSocket connection to the backend and pushes JSON frames.

- WebSocket is designed for browser↔server communication over HTTP
- Between two JVM processes on the same machine, the HTTP upgrade handshake and frame masking are unnecessary overhead
- Requires a WebSocket client library in the telemetry server, or verbose low-level code
- The telemetry server already avoids HTTP dependencies by design (see `reports/PLAIN_JAVA_VS_SPRING_BOOT.md`)

### Option C: gRPC with server streaming

Define a `.proto` service with streaming RPCs. Telemetry runs a gRPC server, backend subscribes to streams.

- Purpose-built for inter-service streaming with typed contracts (protobuf)
- Efficient binary serialization, built-in flow control, deadline propagation
- Adds protobuf compilation step to both projects, plus gRPC runtime dependencies (~5MB)
- Well-suited if this were a production microservices architecture with multiple consumers

### Option D: Shared database polling

Telemetry writes to Oracle (which it does anyway for persistence). Backend polls the database at regular intervals.

- No direct connection between processes — database is the intermediary
- Backend polls `sector_snapshots` and `session_events` tables every 1-2 seconds
- Adds 1-2 seconds of latency to the live path: telemetry → DB write → backend poll → WebSocket → portal
- Extra read load on the database for data that's immediately available in memory

### Option E: Plain TCP socket + JSON-lines (chosen)

Telemetry opens a persistent TCP connection to the backend and sends newline-delimited JSON messages at ~1Hz for race state, plus discrete messages for session lifecycle events.

## Why Plain TCP Won

**Zero new dependencies.** `java.net.Socket` and `java.io.OutputStream` are in the JDK. The telemetry server stays zero-dependency, consistent with the decision in `reports/PLAIN_JAVA_VS_SPRING_BOOT.md`.

**Natural fit for a persistent data stream.** TCP provides a reliable, ordered byte stream — exactly what's needed for pushing continuous snapshots. No per-message connection overhead (REST), no HTTP framing (WebSocket), no compilation step (gRPC).

**Sub-second latency.** Data goes from telemetry memory → socket write → backend socket read → WebSocket broadcast. No database round-trip, no polling interval. The full pipeline (UDP packet arrival to portal display) is under 100ms in the common case.

**One channel for everything.** The same TCP connection carries both continuous race state and discrete lifecycle events (`sessionStarted`, `sessionEnded`). This avoids mixing protocols — no "use TCP for streaming but REST for events" split that adds complexity for no benefit.

**Simple failure model.** Connection drops are detected immediately by both sides (broken pipe on write, EOF on read). Telemetry reconnects with exponential backoff. Backend accepts a new connection and resumes. No partial state to reconcile.

## Why the Others Lost

**REST** adds HTTP overhead to every message and requires the telemetry server to handle HTTP client concerns (timeouts, retries, connection pooling) for a use case that's fundamentally streaming, not request/response.

**WebSocket** is HTTP with extra steps. The upgrade handshake, frame encoding, and masking are designed for browser security constraints that don't apply between two JVM processes on the same network.

**gRPC** is the right tool for production microservices with multiple consumers and strict API contracts. For a single-instance PoC with one producer and one consumer, protobuf compilation and gRPC runtime are overhead without payoff.

**Database polling** works but adds unnecessary latency and DB load. The data exists in telemetry memory — sending it directly to the backend is strictly faster and simpler than writing it to disk and reading it back.

## Single Backend Instance Assumption

This architecture assumes a single backend instance. The telemetry server connects to one backend, and that backend holds the live race state in memory.

This is appropriate for a PoC. If multiple backend instances were needed (load balancing, high availability), the options would be:
- A message broker (Redis Pub/Sub, Kafka) between telemetry and backends
- Telemetry connecting to all backend instances
- A shared state store (Redis) that backends read from

None of these are needed now, and adding them prematurely would add infrastructure complexity without benefit.

## Reconnection Strategy

Telemetry reconnects with **exponential backoff**: 3s → 6s → 12s → 24s → capped at 30s. The delay resets to 3s on successful connection. This prevents:

- Hammering the backend during a restart (fixed 3s retry would send ~10 connection attempts during a 30s restart)
- Unnecessary delay after a brief network glitch (the first retry at 3s catches transient failures fast)

On backend restart, it loses in-memory state (current session, connected clients). Recovery path:
1. Backend queries the database for active session state (catch-up)
2. Telemetry reconnects within seconds (backoff resets after last successful connection)
3. Live streaming resumes automatically

## JSON-lines Protocol

Newline-delimited JSON (`\n`-separated) was chosen over alternatives:

- **Length-prefixed binary:** More efficient but harder to debug. `tail -f` on a TCP stream shows readable JSON; binary requires tooling.
- **Protobuf/MessagePack:** Better compression but adds serialization dependencies. At ~1KB per message at 1Hz, bandwidth is irrelevant.
- **Custom binary format:** The telemetry server already parses binary UDP packets. Adding another binary format between telemetry and backend is unnecessary — the parsing cost is on the UDP side, not the TCP side.

JSON-lines is human-readable, trivially parseable in any language, and debug-friendly. At 1Hz with ~1KB messages, performance is not a concern.

## Key Takeaway

The simplest reliable transport between two processes on the same machine is a TCP socket. Everything else — HTTP, WebSocket, gRPC, message brokers — adds a layer of abstraction designed for problems (browser security, service discovery, schema evolution, fan-out) that don't exist in this PoC. When the problem is "send a JSON blob once per second from process A to process B," a socket and a newline are sufficient.
