# Tasks: Desktop Client Baseline & Character Creation Pipeline

**Feature**: `002-desktop-client`  
**Date**: 2026-09-06  
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize the `client-desktop` module, Maven dependency management, and logging infrastructure.

- [x] T001 Configure parent `pom.xml` to declare `client-desktop` module and manage LWJGL 3.4.2 and Dear ImGui 1.86.11 dependencies in `pom.xml`
- [x] T002 Create `client-desktop/pom.xml` declaring dependencies on `client-sdk`, `common-protocol`, LWJGL 3.4.2 (core, glfw, opengl with platform native classifiers), Dear ImGui 1.86.11, and Micronaut in `client-desktop/pom.xml`
- [x] T003 [P] Create desktop client configuration properties in `client-desktop/src/main/java/com/example/client/desktop/properties/DesktopClientProperties.java`
- [x] T004 [P] Create desktop client logback configuration in `client-desktop/src/main/resources/logback.xml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core engine, main-thread task scheduler, GLFW windowing, and Dear ImGui rendering loop that MUST be complete before UI states can execute.

- [x] T005 [P] Transplant task scheduling contracts in `client-desktop/src/main/java/com/example/client/desktop/concurrent/TaskHandle.java`, `client-desktop/src/main/java/com/example/client/desktop/concurrent/DefaultTaskHandle.java`, `client-desktop/src/main/java/com/example/client/desktop/concurrent/TaskStatus.java`, and `client-desktop/src/main/java/com/example/client/desktop/concurrent/TaskScheduler.java`
- [x] T006 Implement virtual-thread task scheduler and main-thread foreground queue dispatching in `client-desktop/src/main/java/com/example/client/desktop/concurrent/TaskSchedulerService.java`
- [x] T007 [P] Transplant service lifecycle contracts and application loop policies in `client-desktop/src/main/java/com/example/client/desktop/engine/IService.java`, `client-desktop/src/main/java/com/example/client/desktop/engine/ApplicationLoopPolicy.java`, and `client-desktop/src/main/java/com/example/client/desktop/engine/DefaultEngineConfiguration.java`
- [x] T008 [P] Transplant GLFW context management and error callbacks in `client-desktop/src/main/java/com/example/client/desktop/engine/services/glfw/GlfwContextService.java`
- [x] T009 [P] Transplant window service and buffer swapping in `client-desktop/src/main/java/com/example/client/desktop/engine/services/window/WindowProperties.java` and `client-desktop/src/main/java/com/example/client/desktop/engine/services/window/WindowService.java`
- [x] T010 [P] Transplant Dear ImGui GLFW/OpenGL render pipeline in `client-desktop/src/main/java/com/example/client/desktop/engine/services/imgui/ImGuiService.java`
- [x] T011 [P] Transplant delta time and FPS tracking in `client-desktop/src/main/java/com/example/client/desktop/engine/services/time/FrameTimeService.java`
- [x] T012 Implement core display loop with MPSC foreground queue draining in `client-desktop/src/main/java/com/example/client/desktop/engine/Engine.java`
- [x] T013 [P] Transplant application state machine contracts and service in `client-desktop/src/main/java/com/example/client/desktop/engine/services/state/ApplicationState.java` and `client-desktop/src/main/java/com/example/client/desktop/engine/services/state/ApplicationStateService.java`
- [x] T014 Implement unit tests for task scheduler virtual-thread execution and main-thread queue processing in `client-desktop/src/test/java/com/example/client/desktop/TaskSchedulerServiceTest.java`

**Checkpoint**: Core graphics and concurrency foundation ready.

---

## Phase 3: User Story 1 - Desktop Authentication & Gateway Connection (Priority: P1) 🎯 MVP

**Goal**: Launch desktop application, display Login panel, submit credentials to gateway via `client-sdk`, and advance state upon authentication.

**Independent Test**: Run launcher, submit credentials, verify transition from `UnauthenticatedState` to `AuthenticatedState` on success or error display on failure.

- [x] T015 [P] [US1] Implement `ClientState` observable store maintaining `AppPhase`, `accountId`, `authToken`, and `lastErrorMessage` in `client-desktop/src/main/java/com/example/client/desktop/net/ClientState.java`
- [x] T016 [P] [US1] Implement `DesktopNetworkAdapter` implementing `ClientCallback` from `client-sdk` and dispatching packet events to main thread in `client-desktop/src/main/java/com/example/client/desktop/net/DesktopNetworkAdapter.java`
- [x] T017 [P] [US1] Transplant and adapt `LoginPanel` with credentials input, button state locking, error banners, and intent polling in `client-desktop/src/main/java/com/example/client/desktop/ui/LoginPanel.java`
- [x] T018 [US1] Transplant and adapt `UnauthenticatedState` binding `LoginPanel` to `ClientState.sendLogin` and transitioning on auth success in `client-desktop/src/main/java/com/example/client/desktop/state/UnauthenticatedState.java`
- [x] T019 [US1] Create client launcher entry-point with Micronaut DI bootstrap and application loop policy in `client-desktop/src/main/java/com/example/client/desktop/Launcher.java`
- [x] T020 [P] [US1] Implement unit test for authentication state transitions and error handling in `client-desktop/src/test/java/com/example/client/desktop/ClientStateTest.java`

**Checkpoint**: User Story 1 MVP fully functional and testable.

---

## Phase 4: User Story 2 - Comprehensive Character Creation & Customization (Priority: P1)

**Goal**: Present character creation form supporting 8 canonical races, race-locked sizes, 16 face style permutations, 6 starting jobs, and 3 nations, transmitting `raceId` and `gender` as distinct fields.

**Independent Test**: Open creation form, verify Tarutaru size is locked to Small (0), Galka locked to Large (2), Hume/Elvaan/Mithra selectable (0..2), face variant toggle calculates 0..15, and valid submission creates character in database.

- [x] T021 [P] [US2] Add domain enums `RaceId` and `NationId` in `common-protocol/src/main/java/com/example/common/protocol/RaceId.java` and `common-protocol/src/main/java/com/example/common/protocol/NationId.java`
- [x] T022 [US2] Transplant and adapt `CharacterPanel` creation form enforcing 8 races, forced sizes, 16 face permutations, 6 jobs, and 3 nations in `client-desktop/src/main/java/com/example/client/desktop/ui/CharacterPanel.java`
- [x] T023 [US2] Implement `sendCharacterCreate` in `ClientState` dispatching `CreateCharacterRequest` with separate `raceId` and `gender` in `client-desktop/src/main/java/com/example/client/desktop/net/ClientState.java`
- [x] T024 [P] [US2] Implement unit tests for character creation validation rules (race-locked sizes, face encoding, gender derivation) in `client-desktop/src/test/java/com/example/client/desktop/CharacterCreationValidationTest.java`

**Checkpoint**: User Story 2 complete with 100% domain parity.

---

## Phase 5: User Story 3 - Character Roster Management & World Entry (Priority: P2)

**Goal**: Display existing character summaries (name, race, job, level, zone ID), select character, dispatch world play request (`JoinWorldRequest`), and handle character deletion.

**Independent Test**: Load character roster with existing characters, click select/delete, and verify "Play" dispatches world entry without sending `characterId` from client.

- [x] T025 [US3] Extend `CharacterPanel` to render character cards with name, race label, job label, level, zone ID, and select/delete/play actions in `client-desktop/src/main/java/com/example/client/desktop/ui/CharacterPanel.java`
- [x] T026 [P] [US3] Transplant and adapt `AuthenticatedState` handling character list retrieval, selection intents, and sign out in `client-desktop/src/main/java/com/example/client/desktop/state/AuthenticatedState.java`
- [x] T027 [US3] Transplant and adapt `CharacterSelectedState` handling play request dispatch and transition to in-game state in `client-desktop/src/main/java/com/example/client/desktop/state/CharacterSelectedState.java`
- [x] T028 [US3] Implement `sendCharacterSelect`, `sendCharacterDelete`, and `sendPlay` (dispatching `JoinWorldRequest(authToken)`) in `client-desktop/src/main/java/com/example/client/desktop/net/ClientState.java`

**Checkpoint**: User Story 3 complete.

---

## Phase 6: User Story 4 - In-World Session HUD & Real-Time Latency Monitoring (Priority: P2)

**Goal**: Display in-game HUD with character identity, session status, live keepalive heartbeat health, round-trip latency in ms, manual ping trigger, and logout/return controls.

**Independent Test**: Enter in-game state, observe live RTT latency updating continuously, click "Ping Now", and click "Logout" to return to login.

- [x] T029 [P] [US4] Transplant and adapt `InGamePanel` displaying active character, session ID, live RTT latency, manual ping button, and logout in `client-desktop/src/main/java/com/example/client/desktop/ui/InGamePanel.java`
- [x] T030 [US4] Implement keepalive telemetry and periodic `PingMessage` loop in `client-desktop/src/main/java/com/example/client/desktop/net/DesktopKeepAliveService.java`
- [x] T031 [US4] Transplant and adapt `InGameState` binding HUD to keepalive telemetry and handling `PongMessage` packets in `client-desktop/src/main/java/com/example/client/desktop/state/InGameState.java`

**Checkpoint**: User Story 4 complete.

---

## Phase 7: User Story 5 - Live Rolling Debug Console & Session Diagnostics (Priority: P3)

**Goal**: Provide on-screen rolling debug log console with timestamps and auto-scroll for diagnosing client events and responses.

**Independent Test**: Trigger client events and verify chronological log entries appear in the debug window with auto-scrolling.

- [x] T032 [P] [US5] Transplant and adapt `DebugLogPanel` with timestamped log formatting and auto-scroll in `client-desktop/src/main/java/com/example/client/desktop/ui/DebugLogPanel.java`
- [x] T033 [US5] Wire `DebugLogPanel` rendering across `UnauthenticatedState`, `AuthenticatedState`, `CharacterSelectedState`, and `InGameState` in `client-desktop/src/main/java/com/example/client/desktop/state/`

**Checkpoint**: User Story 5 complete.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Architectural fitness verification, headless test execution safety, and end-to-end quickstart validation.

- [x] T034 [P] Add ArchUnit test verifying `client-desktop` never leaks into server modules in `platform-modulith/src/test/java/com/example/architecture/ClientDesktopIsolationArchTest.java`
- [x] T035 Execute headless build validation and unit test suites across all modules via `./mvnw clean test`
- [x] T036 Run quickstart validation scenario against running modulith per `specs/002-desktop-client/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — starts immediately.
- **Phase 2 (Foundational)**: Depends on Phase 1 — BLOCKS all user stories.
- **Phase 3 (User Story 1 - MVP)**: Depends on Phase 2.
- **Phase 4 (User Story 2)**: Depends on Phase 2 (and integrates with `CharacterPanel` from Phase 3).
- **Phase 5 (User Story 3)**: Depends on Phase 3 and Phase 4.
- **Phase 6 (User Story 4)**: Depends on Phase 5 (entering world).
- **Phase 7 (User Story 5)**: Can be integrated once Phase 3 is active.
- **Phase 8 (Polish)**: Depends on all user stories being complete.

### Parallel Opportunities

- **Phase 1**: T003 and T004 can run in parallel with T001/T002.
- **Phase 2**: T005, T007, T008, T009, T010, T011, and T013 can execute in parallel once `pom.xml` is configured.
- **Phase 3**: T015, T016, and T017 can be implemented in parallel.
- **Phase 4**: T021 and T024 can run in parallel with T022.
- **Phase 5**: T026 can run in parallel with T025.
- **Phase 6**: T029 can run in parallel with T030.

---

## Implementation Strategy

### MVP First (User Story 1 Only)
1. Complete Phase 1 (Setup) and Phase 2 (Foundational Engine).
2. Complete Phase 3 (User Story 1: Desktop Authentication & Gateway Connection).
3. **STOP and VALIDATE**: Verify desktop application launches, renders ImGui login panel, connects to gateway, and authenticates.

### Incremental Delivery
1. Foundation + US1 $\rightarrow$ Working desktop launcher & login client (MVP).
2. Add US2 $\rightarrow$ Full FFXI character creator with 100% domain parity.
3. Add US3 $\rightarrow$ Character selection and authoritative world play entry.
4. Add US4 $\rightarrow$ In-world session HUD with live RTT keepalive latency.
5. Add US5 $\rightarrow$ Rolling debug console across all states.
6. Polish $\rightarrow$ ArchUnit isolation test, clean builds, and quickstart run.
