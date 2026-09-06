# Implementation Plan: Desktop Client Baseline & Character Creation Pipeline

**Branch**: `002-desktop-client` | **Date**: 2026-09-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-desktop-client/spec.md`

## Summary

Harvest and transplant the mature desktop presentation layer from `reference/ffxi-java/catalyst/client` into a new Maven module, **`client-desktop`**, in `netty-quic-gateway-demo`. 

The architecture features:
1. **Graphics & Windowing**: LWJGL 3.4.2 (GLFW + OpenGL 4.6) and Dear ImGui 1.86.11 immediate-mode rendering.
2. **Main-Thread Concurrency**: `TaskSchedulerService` running asynchronous operations on Java 25 Virtual Threads while queueing UI callbacks into a thread-safe `foregroundQueue` drained deterministically on the OS main thread inside `Engine.tick()`.
3. **Network Decoupling**: Complete isolation from raw Netty transport; all networking connects through `client-sdk` via `QuicClient` and `ClientCallback`.
4. **Domain Parity**: 100% preservation of FFXI character creation logic (8 canonical races, race-specific size locking, 16 facial variations, 6 starting jobs, 3 starting nations) and character roster summaries.

---

## Technical Context

**Language/Version**: Java 25 with Virtual Threads enabled  
**Primary Dependencies**:
- LWJGL 3.4.2 (`lwjgl`, `lwjgl-glfw`, `lwjgl-opengl` with platform native classifiers)
- Dear ImGui 1.86.11 (`io.github.spair:imgui-java-binding`, `imgui-java-lwjgl3`, `imgui-java-natives-linux`)
- Micronaut 5.1.0 (`micronaut-inject`, `micronaut-context`, `micronaut-runtime`)
- `client-sdk` (internal dependency for QUIC network transport)
- `common-protocol` (internal dependency for wire DTOs and domain enums)
- SLF4J 2.0+ / Logback Classic  

**Storage**: N/A for desktop client (server persistence managed via PostgreSQL in `platform-modulith`)  
**Testing**: JUnit 5, Mockito, headless test execution guards  
**Target Platform**: Desktop OS (Linux x86_64, macOS, Windows) with OpenGL 4.6+ support  
**Project Type**: Desktop GUI Client Module (`client-desktop`)  
**Performance Goals**:
- Stable 60+ FPS UI rendering
- < 2.0s startup time to Login screen
- < 500ms round-trip state transition latency  
**Constraints**:
- Absolute main-thread affinity for GLFW and OpenGL calls
- Zero direct Netty channel manipulation in `client-desktop` (all delegated to `client-sdk`)
- Headless test execution safety (no build failures on headless CI nodes)  
**Scale/Scope**: 1 new Maven module, ~15 Java classes harvested and adapted from reference.

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Constitutional Principle                     | Status   | Evaluation & Adherence Proof                                                                                                                                                                  |
|:---------------------------------------------|:---------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **I. Modular Isolation & Clean Boundaries**  | **PASS** | `client-desktop` depends strictly downward on `client-sdk` and `common-protocol`. `platform-modulith` and `common-protocol` NEVER depend on `client-desktop`. Edge gateway and domain services remain completely unaffected. |
| **II. Zero-Leak ByteBuf & Strict Transport** | **PASS** | `client-desktop` does not handle raw `ByteBuf` allocations; all binary buffer lifecycle management is encapsulated within `client-sdk`'s `QuicClient` and codecs.                           |
| **III. Deterministic Binary Serialization**  | **PASS** | All communication uses centrally registered Apache Fory wire packets in `common-protocol` (`LoginRequest`, `CharacterListRequest`, `CreateCharacterRequest`, etc.).                         |
| **IV. Stateless Cryptographic Security**     | **PASS** | Authentication tokens and character sessions are resolved authoritatively on the backend gateway and domain services, not trusted from client claims.                                       |
| **V. Deterministic Simulation Concurrency**   | **PASS** | Main render loop uses an actor-style display loop draining `foregroundQueue` MPSC tasks deterministically, preventing race conditions with background network threads.                         |

---

## Project Structure

### Documentation (this feature)

```text
specs/002-desktop-client/
├── spec.md              # Feature specification
├── plan.md              # This file (implementation plan)
├── research.md          # Phase 0 architectural decisions & technical context
├── data-model.md        # Phase 1 domain entities, enums, & state machine
├── quickstart.md        # Phase 1 build, test, and manual verification guide
├── contracts/           # Phase 1 interface & lifecycle contracts
│   ├── network-adapter-contract.md
│   └── ui-panel-contract.md
└── checklists/
    └── requirements.md  # Quality validation checklist
```

### Source Code Layout

```text
netty-quic-gateway-demo/
├── pom.xml                               <-- Register client-desktop module & manage LWJGL/ImGui versions
├── common-protocol/                      <-- Add RaceId, JobId, NationId enums & update CharacterCreateRequest
│   └── src/main/java/com/example/protocol/
│       ├── domain/
│       │   ├── RaceId.java
│       │   ├── JobId.java
│       │   └── NationId.java
│       └── packet/
│           ├── CharacterCreateRequest.java
│           └── CharacterSummary.java
├── client-sdk/                           <-- Existing headless QUIC client (unchanged)
├── client-desktop/                       <-- NEW MODULE
│   ├── pom.xml                           <-- Declares dependencies on client-sdk, LWJGL, ImGui
│   └── src/
│       ├── main/java/com/example/client/desktop/
│       │   ├── Launcher.java             <-- Desktop entry-point
│       │   ├── concurrent/               <-- Harvested from catalyst/common/concurrency
│       │   │   ├── TaskScheduler.java
│       │   │   ├── TaskSchedulerService.java
│       │   │   ├── TaskHandle.java
│       │   │   ├── DefaultTaskHandle.java
│       │   │   └── TaskStatus.java
│       │   ├── engine/                   <-- Harvested from catalyst/client/engine
│       │   │   ├── Engine.java
│       │   │   ├── IService.java
│       │   │   ├── ApplicationLoopPolicy.java
│       │   │   └── services/
│       │   │       ├── glfw/GlfwContextService.java
│       │   │       ├── window/WindowService.java
│       │   │       ├── window/WindowProperties.java
│       │   │       ├── imgui/ImGuiService.java
│       │   │       ├── time/FrameTimeService.java
│       │   │       └── state/
│       │   │           ├── ApplicationState.java
│       │   │           └── ApplicationStateService.java
│       │   ├── state/                    <-- Harvested from catalyst/client/application/state
│       │   │   ├── UnauthenticatedState.java
│       │   │   ├── AuthenticatedState.java
│       │   │   ├── CharacterSelectedState.java
│       │   │   └── InGameState.java
│       │   ├── ui/                       <-- Harvested from catalyst/client/application/ui
│       │   │   ├── LoginPanel.java
│       │   │   ├── CharacterPanel.java   <-- Full FFXI character creation & roster
│       │   │   ├── InGamePanel.java      <-- Live RTT & keepalive status
│       │   │   └── DebugLogPanel.java    <-- Live scrolling debug log
│       │   ├── net/                      <-- Network adapter bridging QuicClient to ClientState
│       │   │   ├── DesktopNetworkAdapter.java
│       │   │   └── ClientState.java
│       │   └── properties/
│       │       └── DesktopClientProperties.java
│       └── test/java/com/example/client/desktop/
│           ├── TaskSchedulerServiceTest.java
│           ├── ClientStateTest.java
│           ├── CharacterCreationValidationTest.java
│           └── HeadlessEngineTest.java
└── reference/ffxi-java/                  <-- Legacy reference source (ignored in git)
```

---

## Complexity Tracking

| Violation | Why Needed                                                  | Simpler Alternative Rejected Because |
|:----------|:------------------------------------------------------------|:-------------------------------------|
| *None*    | All principles satisfied without constitutional exceptions. | N/A                                  |
