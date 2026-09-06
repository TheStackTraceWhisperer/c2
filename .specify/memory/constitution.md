<!--
Sync Impact Report
Version: 1.0.0 -> 1.1.0 (Zone Simulation Concurrency Model & Virtual Thread Pinning Defenses)
Modified Principles:
  - V. Deterministic Simulation & Spatial Grid Isolation -> Renamed to "V. Deterministic Simulation & Zone Concurrency Model" and expanded with single-threaded tick loops (Actor pattern), MPSC packet queuing, and non-blocking persistence/transfer offloading.
Added Sections / Constraints:
  - Technical Constraints: Added Virtual Thread Carrier Pinning Defense (synchronized avoidance, -Djdk.tracePinnedThreads=full) and Bounded JDBC Connection Pooling rules.
Removed Sections:
  - None
Follow-up TODOs:
  - None
-->

# Netty QUIC Gateway Demo Constitution

## Core Principles

### I. Modular Isolation & Clean Boundaries
- **Protocol Independence**: `common-protocol` MUST remain pure and MUST NOT depend on server runtime, gateway, or client infrastructure.
- **Edge Gateway Isolation**: The Edge Gateway (`QuicGateway`, relay handlers) MUST ONLY handle routing, packet relaying, token verification, and rate limiting; it MUST NOT contain domain logic or directly depend on domain handlers (`login`, `lobby`, `world`, `zone`).
- **Domain Service Decoupling**: Domain services (`login`, `lobby`, `world`, `zone`) MUST NOT depend on each other's internal handlers or registries. Inter-domain communication MUST occur strictly through explicit event envelopes, database transactions, or asynchronous messages.
- **Client SDK Separation**: Server runtime code MUST NEVER depend on `client-sdk`. The client SDK is strictly for external clients and test harnesses.
- *Rationale*: Preserves modularity, independent scalability, and testability, preventing architectural entanglement and allowing seamless transition between single-process modulith and distributed services.

### II. Zero-Leak ByteBuf & Strict Transport Lifecycle
- **Strict Reference Counting**: All Netty `ByteBuf` allocations across stream handlers, codecs, relay handlers, and packet queues MUST adhere to explicit reference-counting lifecycle management (`retain` and `release`).
- **Guaranteed Release**: Every code path handling network buffers—including successful dispatches, routing errors, unhandled routes, dropped packets, and channel teardowns—MUST guarantee buffer release in a `finally` block or transfer ownership to a downstream consumer.
- **Leak Detection Compliance**: Tests and local development environments MUST run with Netty leak detection enabled (`-Dio.netty.leakDetection.level=PARANOID` or `ADVANCED`) and pass with zero resource leak reports.
- *Rationale*: High-throughput UDP/QUIC network systems experience fatal native memory exhaustion and off-heap corruption if reference-counted buffers leak under load or during abrupt client disconnects.

### III. Deterministic Binary Serialization & Drift-Free Schemas
- **Unified Wire Registries**: Binary serialization across client and server MUST use deterministic Apache Fory schemas.
- **Collision & Drift Prevention**: Wire type IDs MUST be centrally managed and validated; dual registries with manual ID assignments that risk ID collisions or unaligned registrations between client and backend are forbidden.
- **Backward-Compatible Evolution**: Changes to serialization schemas MUST preserve wire compatibility, ensuring older client or service builds fail gracefully with structured error codes rather than corrupting deserialization pipelines.
- *Rationale*: Low-latency binary protocol throughput requires zero-overhead serialization; unaligned wire IDs cause fatal runtime deserialization exceptions, silent packet drops, and protocol drift.

### IV. Stateless Cryptographic Security & Context Resolution
- **Asymmetric Token Verification**: All client connections transitioning beyond public login endpoints MUST be authenticated via cryptographically verified asymmetric RSA-2048 JWT tokens.
- **Trust-Free Identity Resolution**: Domain handlers MUST resolve player context (`accountId`, `characterId`, `zoneId`, `worldId`) strictly from validated session context or verified JWT claims, NEVER from client-supplied identity parameters or hardcoded defaults.
- **Secure Credential Storage**: Passwords and secrets MUST be hashed using Argon2 with robust memory and iteration cost parameters; plaintext or reversibly encrypted credentials are strictly prohibited.
- *Rationale*: Prevents identity spoofing, unauthorized character manipulation, and session hijacking in distributed game environments.

### V. Deterministic Simulation & Zone Concurrency Model
- **Discrete Spatial Partitioning**: World and Zone spatial processing MUST execute on fixed tick intervals using localized spatial grids (`SpatialGrid`) and Area of Interest (AoI) filtering.
- **Dedicated Single-Threaded Tick Loop**: All entity state mutations, spatial queries, and physics/simulation logic within an active zone instance MUST execute sequentially on a dedicated single-threaded tick cycle (Actor pattern). Entity state within the simulation MUST NOT require internal locking or synchronization.
- **Asynchronous Boundary Ingestion**: Network I/O, QUIC stream handling, ping/pong heartbeats, and connection lifecycles MUST remain decoupled from the tick loop, enqueuing inbound frames into thread-safe MPSC packet queues (`PacketQueue`) that the zone loop drains deterministically at the start of each tick.
- **Non-Blocking Tick Guarantee**: The zone simulation tick loop MUST NEVER execute blocking I/O (such as disk access, direct database queries, or synchronous network round-trips). State snapshotting, hot storage synchronization, and cross-zone transfer handoffs MUST be decoupled and executed asynchronously out-of-band without stalling the tick cadence.
- **Transactional State Handoffs**: Cross-zone and cross-world transitions MUST follow transactional, idempotent state transfer semantics to eliminate character duplication, ghost sessions, or desynchronized spatial snapshots.
- *Rationale*: Guarantees bounded CPU and network broadcast complexity as entity density increases, prevents race conditions inside the simulation without locking overhead, and ensures stable server tick rates during persistence and zone transfers.

## Technical Constraints & Platform Standards

- **Runtime & Language**: Java 25 utilizing Virtual Threads (`VirtualThreadTaskDispatcher`) for blocking tasks, I/O dispatch, and asynchronous workflow execution.
- **Virtual Thread Carrier Pinning Defense**: Code executing on Virtual Threads MUST avoid `synchronized` blocks around blocking calls or I/O operations (favoring `java.util.concurrent.locks.ReentrantLock`). Test and development environments SHOULD run with `-Djdk.tracePinnedThreads=full` to detect and eliminate carrier thread pinning.
- **Bounded JDBC Connection Pooling**: Database access initiated from Virtual Threads MUST remain strictly governed by bounded connection pools (e.g. HikariCP) to prevent database exhaustion under massive concurrency.
- **Application Framework**: Micronaut 5.1.0 with ahead-of-time (AOT) optimizations and native Docker container packaging.
- **Networking Stack**: Netty Incubator QUIC (`io.netty.incubator:netty-incubator-codec-classes-quic` / `netty-incubator-codec-native-quic`) running over UDP.
- **Data Architecture**: PostgreSQL with schema-level isolation (`game`, `player`, `infra`), accessed via clean JDBC repositories and managed Flyway migrations.
- **Cloud-Native Deployment**: Kubernetes-native architecture managed via Fabric8 Kubernetes Client operator (`MmoFleetOperator`) and reproducible k3d cluster harnesses.
- **Error Signaling & Client Feedback**: Edge Gateway and backend services MUST NOT silently drop packets; unauthenticated requests, rate-limit exceedances, or missing routes MUST return structured reject frames or error responses to the client.

## Quality Gates & Verification Workflow

- **Architectural Fitness Tests**: Every build and pull request MUST pass automated ArchUnit rules (`ArchitectureTest`) verifying package isolation, service boundaries, and dependency directions.
- **Integration Test Coverage**: End-to-end user journeys (`PlayerJourneyIT`, `GatewayRoutingIT`, `GatewaySecurityIT`, `ZoneSimulationIT`) MUST be validated against containerized databases and headless QUIC client drivers.
- **Automated Cluster Validation**: Multi-node and operator lifecycle flows MUST validate cleanly using the end-to-end k3d test harness (`scripts/run-e2e.sh`).
- **Structured Observability**: All components MUST use SLF4J with Logback; log statements MUST be structured, informative, and free of redundant module prefix tags or unformatted debug output.

## Governance

- **Supreme Architectural Authority**: This constitution defines the non-negotiable architectural principles and technical standards for the `netty-quic-gateway-demo` repository. It supersedes informal agreements and local conventions.
- **Amendment Procedure**: Amendments to this document require a formal pull request containing:
  1. A clear statement of the proposed modification.
  2. Architectural rationale and alternatives considered.
  3. An impact analysis covering existing modules, protocol definitions, and database migrations.
  4. Explicit approval from project maintainers.
- **Versioning Policy**:
  - **MAJOR (x.0.0)**: Removal, reversal, or breaking redefinition of core principles or architectural boundaries.
  - **MINOR (1.x.0)**: Addition of new principles, new sections, or materially expanded governance rules.
  - **PATCH (1.0.x)**: Non-semantic clarifications, typo corrections, formatting adjustments, or wording refinements.
- **Compliance & Review Expectations**: All pull requests and automated workflows MUST verify adherence to this constitution. Code introducing architectural boundary violations, ByteBuf leak risks, unauthenticated context shortcuts, or breaking serialization changes will be rejected.

**Version**: 1.1.0 | **Ratified**: 2026-08-14 | **Last Amended**: 2026-09-06
