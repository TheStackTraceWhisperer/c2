# Tasks: Canonical ClientState Unification & World Visualization

**Feature Branch**: `main` (feature `003-client-state-world-visualization`)  
**Input**: Design artifacts from `specs/003-client-state-world-visualization/`  
**Status**: Ready for Implementation  

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify project build dependencies, compiler flags, and test harness settings.

- [x] T001 Verify compiler configuration, Lombok annotation processing, and module dependencies in `pom.xml` and `client-desktop/pom.xml`
- [x] T002 [P] Verify test environment and Netty leak detection flags in `pom.xml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core state model enhancements and network adapter methods that MUST be complete before user stories can be implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T003 Enhance canonical `ClientState` in `client-sdk/src/main/java/com/example/client/state/ClientState.java` with local player orientation (`yaw`, `pitch`), latency telemetry (`currentLatencyMs`), position update overload with yaw/pitch, and atomic reset flushes
- [x] T004 [P] Update `client-sdk/src/main/java/com/example/client/handler/PongPacketHandler.java` to record RTT latency on canonical `ClientState`
- [x] T005 Expand `client-desktop/src/main/java/com/example/client/desktop/net/DesktopNetworkAdapter.java` to provide outbound network dispatch methods (`sendLogin`, `sendCharacterList`, `sendCharacterCreate`, `sendCharacterDelete`, `sendCharacterSelect`, `sendPlay`, `sendMovement`, `sendPing`, `sendLogout`) and register main-thread callbacks for AoI delta broadcasts and movement responses

**Checkpoint**: Foundation ready — User Story 1 implementation can now begin.

---

## Phase 3: User Story 1 - Single Canonical Client State (Priority: P1) 🎯 MVP

**Goal**: Eliminate the duplicate `ClientState` in `client-desktop`, rewire all desktop presentation panels and state machines to use `client-sdk`'s `ClientState`, ensuring a single authoritative state store and clean separation of state storage from network actions.

**Independent Test**: Run client authentication, character selection, and world entry; verify that UI panels read directly from `com.example.client.state.ClientState`, network requests dispatch via `DesktopNetworkAdapter`, and zero duplicate state classes exist.

### Implementation for User Story 1

- [x] T006 [US1] Remove duplicate class `client-desktop/src/main/java/com/example/client/desktop/net/ClientState.java`
- [x] T007 [P] [US1] Rewire `client-desktop/src/main/java/com/example/client/desktop/ui/LoginPanel.java` to read credentials and error messages directly from canonical `com.example.client.state.ClientState`
- [x] T008 [P] [US1] Rewire `client-desktop/src/main/java/com/example/client/desktop/ui/CharacterPanel.java` to read character roster and selection directly from canonical `com.example.client.state.ClientState`
- [x] T009 [P] [US1] Update `client-desktop/src/main/java/com/example/client/desktop/state/UnauthenticatedState.java` to observe canonical `ClientState` and dispatch login actions via `DesktopNetworkAdapter`
- [x] T010 [P] [US1] Update `client-desktop/src/main/java/com/example/client/desktop/state/AuthenticatedState.java` to observe canonical `ClientState` and dispatch character actions via `DesktopNetworkAdapter`
- [x] T011 [P] [US1] Update `client-desktop/src/main/java/com/example/client/desktop/state/CharacterSelectedState.java` to observe canonical `ClientState` and dispatch play/select actions via `DesktopNetworkAdapter`
- [x] T012 [US1] Update unit tests in `client-desktop/src/test/java/com/example/client/desktop/ClientStateTest.java` to validate canonical `ClientState` operations, lifecycle transitions, and reset semantics

**Checkpoint**: At this point, User Story 1 (MVP) is fully functional and all unit tests pass with zero duplicate state classes.

---

## Phase 4: User Story 2 - In-World Spatial Radar & Player Character Visualization (Priority: P1)

**Goal**: Render a real-time 2D spatial radar viewport in `client-desktop` showing the local player centered with yaw heading indicator, and remote player characters within Area of Interest (AoI) with nameplates (name, job abbreviation, level) based on inbound `ZoneStateDeltaMessage` broadcasts.

**Independent Test**: Connect to an active zone, open the radar viewport, and verify the local player is rendered at the center with a heading indicator, and remote player blips update positions dynamically as AoI packets arrive.

### Implementation for User Story 2

- [ ] T013 [P] [US2] Create presentation configuration model `client-desktop/src/main/java/com/example/client/desktop/ui/radar/RadarViewportConfig.java` for radar radius, grid toggle, AoI ring, and nameplate settings
- [ ] T014 [US2] Implement `client-desktop/src/main/java/com/example/client/desktop/ui/radar/WorldRadarPanel.java` rendering 2D tactical radar via Dear ImGui `ImDrawList`: canvas background, border ring, cardinal axes (N/S/E/W), concentric range rings (25m, 50m, 100m, 200m), local player marker with yaw heading indicator, and remote player character blips with floating nameplates (name, job abbreviation, level)
- [ ] T015 [US2] Wire `WorldRadarPanel` into `client-desktop/src/main/java/com/example/client/desktop/state/InGameState.java`, reading `clientState.getRemoteEntities()` and local position `(x, y, z, yaw)` during `onUpdate(dt)`
- [ ] T016 [US2] Connect `ZoneStateDeltaMessage` processing in `client-desktop/src/main/java/com/example/client/desktop/net/DesktopNetworkAdapter.java` to update `clientState` remote player entities on the main thread and notify UI
- [ ] T017 [US2] Create unit test `client-desktop/src/test/java/com/example/client/desktop/ui/radar/WorldRadarPanelTest.java` verifying coordinate projection logic and remote player marker positioning calculations

**Checkpoint**: At this point, User Stories 1 AND 2 are functional, rendering active player characters on the radar viewport.

---

## Phase 5: User Story 3 - Interactive Movement & Position Synchronization (Priority: P2)

**Goal**: Capture keyboard movement inputs (WASD / arrow keys) in `InGameState`, update local character coordinates and facing angle, dispatch throttled `PlayerMovementRequest` packets at 20 Hz to the gateway, and reconcile with authoritative server coordinates.

**Independent Test**: Press WASD keys in-world; verify responsive local movement on the radar, verify movement packets transmitted at 20 Hz, and verify smooth server position reconciliation.

### Implementation for User Story 3

- [ ] T018 [P] [US3] Create `client-desktop/src/main/java/com/example/client/desktop/input/MovementInputHandler.java` to poll keyboard directional keys (WASD, arrows), calculate velocity vector `(vx, vy, vz)` and yaw rotation deltas, and accumulate movement flags (`MovementFlags`)
- [ ] T019 [US3] Implement 20 Hz throttled network dispatch in `client-desktop/src/main/java/com/example/client/desktop/state/InGameState.java` via `DesktopNetworkAdapter.sendMovement(x, y, z, vx, vy, vz, yaw, pitch, moveFlags, tick)` and dispatch immediate idle packet when stopping
- [ ] T020 [US3] Implement authoritative position reconciliation in `client-desktop/src/main/java/com/example/client/desktop/state/InGameState.java` upon receipt of `PlayerMovementResponse`, interpolating local coordinates if server distance delta exceeds 2.0 units
- [ ] T021 [US3] Add unit test `client-desktop/src/test/java/com/example/client/desktop/input/MovementInputHandlerTest.java` verifying flag bitfield generation, velocity calculations, and 20 Hz throttling cadence

**Checkpoint**: At this point, User Stories 1, 2, and 3 are fully functional with interactive movement and server synchronization.

---

## Phase 6: User Story 4 - Zone Telemetry & Spatial Viewport Controls (Priority: P3)

**Goal**: Display real-time zone telemetry (Zone Name, Zone ID, World ID, active nearby player count, X/Y/Z/Yaw) and provide interactive radar controls (zoom scales 25m–200m, grid toggle, AoI circle toggle).

**Independent Test**: Toggle zoom buttons (+ / -), verify range rings rescale appropriately, verify zone name and coordinates update in real time.

### Implementation for User Story 4

- [ ] T022 [P] [US4] Create zone registry helper `client-desktop/src/main/java/com/example/client/desktop/util/ZoneNameRegistry.java` mapping canonical zone IDs (e.g. 100: "West Ronfaure", 230: "Southern San d'Oria") to human-readable zone names
- [ ] T023 [US4] Add telemetry HUD overlay and interactive zoom controls (`+` / `-` buttons, grid toggle, AoI ring toggle) to `client-desktop/src/main/java/com/example/client/desktop/ui/radar/WorldRadarPanel.java`
- [ ] T024 [US4] Update `client-desktop/src/main/java/com/example/client/desktop/ui/InGamePanel.java` to integrate cleanly with `WorldRadarPanel` layout and session controls

**Checkpoint**: All user stories are functional with complete telemetry and viewport controls.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Codebase hygiene, architectural fitness verification, and quickstart validation.

- [ ] T025 [P] Run static verification to ensure exactly zero duplicate `ClientState` classes exist (`git grep -l "class ClientState"`)
- [ ] T026 Run full unit and architecture test suite across all modules (`./mvnw clean test`)
- [ ] T027 Validate against `specs/003-client-state-world-visualization/quickstart.md` scenarios

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Phase 2 — Core MVP prerequisite
- **User Story 2 (Phase 4)**: Depends on Phase 3 (canonical ClientState wired)
- **User Story 3 (Phase 5)**: Depends on Phase 4 (radar viewport rendered)
- **User Story 4 (Phase 6)**: Depends on Phase 4 & Phase 5 (telemetry overlay on radar)
- **Polish (Phase 7)**: Depends on all user stories completed

### Parallel Opportunities

- Within Phase 2: T004 [P] can run in parallel with T003.
- Within Phase 3: T007 [P], T008 [P], T009 [P], T010 [P], T011 [P] can run in parallel once T006 removes the duplicate state class.
- Within Phase 4: T013 [P] can run in parallel with T014.
- Within Phase 5: T018 [P] can run in parallel with T019.
- Within Phase 6: T022 [P] can run in parallel with T023.
- Within Phase 7: T025 [P] can run in parallel with T026.

---

## Implementation Strategy

### MVP First (User Story 1 Only)
1. Complete Phase 1 (Setup) and Phase 2 (Foundational).
2. Complete Phase 3 (User Story 1 - Canonical ClientState Unification).
3. **VALIDATE**: Run `./mvnw clean test` and verify zero duplicate state classes exist.

### Incremental Delivery
1. Add User Story 2: Implement `WorldRadarPanel` and render passive radar with local player heading and remote player blips.
2. Add User Story 3: Implement `MovementInputHandler` and 20 Hz throttled network synchronization with position reconciliation.
3. Add User Story 4: Add zone telemetry overlays and zoom/grid toggle controls.
4. Execute Polish & Quickstart validation.
