# Implementation Plan: Canonical ClientState Unification & World Visualization

**Branch**: `main` (feature `003-client-state-world-visualization`) | **Date**: 2026-09-07 | **Spec**: [specs/003-client-state-world-visualization/spec.md](specs/003-client-state-world-visualization/spec.md)

**Input**: Feature specification from `specs/003-client-state-world-visualization/spec.md`

## Summary

Consolidate duplicate `ClientState` across the client modules into `com.example.client.state.ClientState` in `client-sdk`, strictly separating state representation from outbound network I/O per Spec-Driven Development (SDD) and clean architecture principles. Implement an in-world real-time 2D spatial radar visualizer (`WorldRadarPanel`) in `client-desktop` rendered via Dear ImGui `ImDrawList`. The visualizer renders the local player character with yaw heading indicator, remote player characters within the Area of Interest (AoI) with nameplates (name, job, level), supports keyboard movement (WASD/arrows) with 20 Hz throttled `PlayerMovementRequest` dispatch and server reconciliation, and provides interactive viewport controls (zoom scales 25m–200m, grid toggle, AoI boundary).

---

## Technical Context

**Language/Version**: Java 25 (utilizing virtual threads, records, pattern matching, sealed types)  
**Primary Dependencies**:
- Netty Incubator QUIC (`io.netty.incubator:netty-incubator-codec-classes-quic`)
- Apache Fory binary serialization (`org.apache.fory:fory-core`)
- Dear ImGui (`io.github.spair:imgui-java-binding:1.86.12`, `imgui-java-lwjgl3`)
- LWJGL 3 (`org.lwjgl:lwjgl-glfw`, `org.lwjgl:lwjgl-opengl:3.3.3`)
- Micronaut 4.x / 5.x DI (`@Singleton`, `@Prototype`, `@Named`, `BeanProvider`)
- SLF4J with Logback  

**Storage**: In-memory state in `ClientState` using `ConcurrentHashMap` and `volatile` fields; backend server persists dirty spatial coordinates to PostgreSQL on player transitions/disconnects.  
**Testing**: JUnit 5, AssertJ, Awaitility, ArchUnit (`ArchitectureTest`), BaseIntegrationTest harnesses.  
**Target Platform**: Linux desktop (LWJGL natives-linux, X11/Wayland, OpenGL 4.6 Core profile).  
**Project Type**: Multi-module Maven project comprising Desktop Client (`client-desktop`), Client SDK (`client-sdk`), Shared Protocol (`common-protocol`), and Backend Platform Modulith (`platform-modulith`).  
**Performance Goals**:
- 60+ FPS smooth rendering in ImGui with zero frame stutter.
- Under 16ms (1 frame) latency from AoI delta packet ingestion to radar blip rendering.
- Under 50ms perceptible keyboard movement responsiveness.
- 20 Hz movement packet throttling matching server simulation ticks.  

**Constraints**:
- Exactly ZERO duplicate state classes across modules.
- Strict separation of reactive state storage from active I/O network operations.
- Entities scoped strictly to player characters (no NPCs or monster AI in this milestone).
- Zero ByteBuf leaks across packet pipelines.
- Thread safety between Netty event loop threads and GLFW/OpenGL main UI thread.  

**Scale/Scope**: Support 50+ concurrent remote player entities tracked in AoI without memory leaks or UI thread lag.

---

## Constitution Check

*GATE: Evaluated against Constitution v1.1.0*

| Principle | Assessment | Status |
|---|---|---|
| **I. Modular Isolation & Clean Boundaries** | Consolidating state into `client-sdk/.../ClientState.java` and deleting `client-desktop/.../ClientState.java` restores strict SDD modular boundaries. `client-desktop` depends on `client-sdk`, while `platform-modulith` never depends on `client-sdk`. `DesktopNetworkAdapter` handles outbound actions, keeping state representation pure. | **PASS** |
| **II. Zero-Leak ByteBuf & Strict Transport Lifecycle** | Packet encoding/decoding is managed through existing zero-leak Netty and Fory pipelines in `QuicClient` and `DesktopNetworkAdapter`. No raw unreleased buffers. | **PASS** |
| **III. Deterministic Binary Serialization & Drift-Free Schemas** | Uses existing wire schemas (`PlayerMovementRequest`, `PlayerMovementResponse`, `ZoneStateDeltaMessage`, `EntitySpawnRecord`, `EntityMovementUpdate`) centrally registered in `MessageType`. | **PASS** |
| **IV. Stateless Cryptographic Security & Context Resolution** | Player context (`accountId`, `characterId`, `zoneId`) is bound authoritatively by the backend upon session/token validation. Client asserts movement without spoofing identity. | **PASS** |
| **V. Deterministic Simulation & Zone Concurrency Model** | Backend `ZoneSimulationEngine` continues executing its discrete 20 Hz single-threaded tick loop. Client throttles movement packets to match 20 Hz (50ms) cadence. | **PASS** |

**Gate Result**: All architectural quality gates **PASS**.

---

## Project Structure

### Documentation (this feature)

```text
specs/003-client-state-world-visualization/
├── spec.md              # Feature specification (strictly scoped to player characters)
├── plan.md              # This implementation plan
├── research.md          # Technical decisions and architectural rationale
├── data-model.md        # Authoritative state models, entities, and lifecycle
├── quickstart.md        # Runnable validation and test scenarios
├── contracts/           # Component interfaces and protocol contracts
│   ├── client-state-api.md
│   ├── world-radar-ui.md
│   └── movement-protocol.md
└── tasks.md             # Implementation tasks (to be generated by /speckit-tasks)
```

### Source Code Impact

```text
client-sdk/
└── src/main/java/com/example/client/
    ├── handler/
    │   └── PongPacketHandler.java          # Update clientState.updateLatency()
    └── state/
        └── ClientState.java                # Enhanced canonical state (yaw, latency, etc.)

client-desktop/
└── src/
    ├── main/java/com/example/client/desktop/
    │   ├── net/
    │   │   ├── ClientState.java            # REMOVE (duplicate state deleted)
    │   │   └── DesktopNetworkAdapter.java  # Enhanced with all outbound send* methods & delta handlers
    │   ├── state/
    │   │   ├── AuthenticatedState.java     # Use canonical ClientState & DesktopNetworkAdapter
    │   │   ├── CharacterSelectedState.java # Use canonical ClientState & DesktopNetworkAdapter
    │   │   ├── InGameState.java            # Keyboard input integration, movement dispatch, radar update
    │   │   └── UnauthenticatedState.java   # Use canonical ClientState & DesktopNetworkAdapter
    │   └── ui/
    │       ├── CharacterPanel.java         # Direct reads from canonical ClientState
    │       ├── InGamePanel.java            # Telemetry HUD integration
    │       ├── LoginPanel.java             # Direct reads from canonical ClientState
    │       └── WorldRadarPanel.java        # NEW: 2D tactical radar viewport via ImDrawList
    └── test/java/com/example/client/desktop/
        └── ClientStateTest.java            # Updated to test canonical ClientState integration
```

---

## Complexity Tracking

> No violations of the Constitution or platform standards are introduced.
