# Tasks: Protocol Synchronization, Session Identity Integrity, and Gateway Error Feedback

**Input**: Design documents from `/specs/001-protocol-session-integrity/`
**Prerequisites**: [plan.md](plan.md) (required), [spec.md](spec.md) (required for user stories), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (`[US1]`, `[US2]`, `[US3]`)
- Exact file paths are provided for all tasks

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and baseline environment verification

- [ ] T001 Verify build environment, Java 25 compatibility, and baseline compilation with `./mvnw clean compile`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core wire protocol constants and shared serialization infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T002 [P] Update wire message type constants in `common-protocol/src/main/java/com/example/common/protocol/MessageType.java` (reserve 1-49 for control plane, shift 50-99 for diagnostics, add `GATEWAY_ERROR_RESPONSE = 52`, set `ZONE_STATE_DELTA_BROADCAST = 404`)
- [ ] T003 [P] Create structured error response DTO `GatewayErrorResponse` in `common-protocol/src/main/java/com/example/common/dto/GatewayErrorResponse.java`
- [ ] T004 Centralize common DTO registrations (IDs 50..499) and expose `registerCommon` in `common-protocol/src/main/java/com/example/common/codec/ForySerializationProvider.java`
- [ ] T005 Refactor `platform-modulith/src/main/java/com/example/server/codec/BackendForySerializationProvider.java` to inherit common registrations from `ForySerializationProvider` and register backend control plane IDs (1..4) and `SessionDisconnectedEvent` (99)

**Checkpoint**: Base wire protocol constants and serialization providers aligned — user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Structured Rejection Feedback & Defensive Connection Termination (Priority: P1) 🎯 MVP

**Goal**: Provide immediate structured rejection frames and defensively terminate transport connections on security and protocol policy violations (unauthenticated access, rate limits, out-of-state packets, malformed payloads) while preserving connections for transient backend failures.

**Independent Test**: Execute `GatewaySecurityIT` to verify that unauthenticated requests, rate-limit exceedances, and out-of-state packets return `GatewayErrorResponse` and terminate the QUIC channel within 50ms with zero buffer leaks.

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T006 [P] [US1] Add test cases for unauthenticated packet rejection and channel closure in `platform-modulith/src/test/java/com/example/GatewaySecurityIT.java`
- [ ] T007 [P] [US1] Add test cases for rate-limit rejection and channel closure in `platform-modulith/src/test/java/com/example/GatewaySecurityIT.java`
- [ ] T008 [P] [US1] Add test cases for out-of-state packet injection rejection and channel closure in `platform-modulith/src/test/java/com/example/GatewaySecurityIT.java`

### Implementation for User Story 1

- [ ] T009 [P] [US1] Implement `GatewayErrorResponseHandler` in `client-sdk/src/main/java/com/example/client/handler/GatewayErrorResponseHandler.java`
- [ ] T010 [P] [US1] Add error tracking fields (`lastErrorCode`, `lastErrorMessage`) and state handling in `client-sdk/src/main/java/com/example/client/state/ClientState.java`
- [ ] T011 [US1] Register `GatewayErrorResponseHandler` for `MessageType.GATEWAY_ERROR_RESPONSE` in `client-sdk/src/main/java/com/example/client/QuicClient.java`
- [ ] T012 [US1] Implement defensive error dispatch and connection termination helper in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java`
- [ ] T013 [US1] Enforce defensive termination for rate-limit breaches in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java`
- [ ] T014 [US1] Enforce defensive termination for unauthenticated requests in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java`
- [ ] T015 [US1] Enforce non-terminating `GatewayErrorResponse(SERVICE_UNAVAILABLE)` on unresolvable endpoints in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java`
- [ ] T016 [US1] Add defensive error handling and connection termination for framing/corruption errors in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java`
- [ ] T017 [US1] Verify Netty `ByteBuf` release guarantees in `finally` blocks in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java`

**Checkpoint**: User Story 1 is fully functional and testable independently — Gateway emits structured errors and severs abusive connections immediately.

---

## Phase 4: User Story 2 - Authoritative Session Identity & Untrusted Client Character Context (Priority: P1)

**Goal**: Eliminate caller-asserted actor character IDs from client requests, resolving player character identity strictly from the authoritative Gateway session context across network boundaries.

**Independent Test**: Execute `PlayerJourneyIT` and `ZoneSimulationIT` to verify end-to-end player lifecycle and concurrent multi-client spatial updates attribute actions exclusively to session-derived character context with zero reliance on client-asserted actor IDs.

### Tests for User Story 2

- [ ] T018 [P] [US2] Update test fixture and assertions in `platform-modulith/src/test/java/com/example/PlayerJourneyIT.java` to verify character context resolution without client-supplied actor ID
- [ ] T019 [P] [US2] Add concurrent multi-player isolation test in `platform-modulith/src/test/java/com/example/ZoneSimulationIT.java` verifying separate sessions cannot bleed or spoof character IDs

### Implementation for User Story 2

- [ ] T020 [US2] Refactor `JoinWorldRequest` record in `common-protocol/src/main/java/com/example/world/dto/JoinWorldRequest.java` to remove caller-asserted `characterId` field
- [ ] T021 [US2] Refactor `JoinWorldRequestHandler` in `platform-modulith/src/main/java/com/example/world/handler/JoinWorldRequestHandler.java` to derive character identity strictly from `SessionContext.CHARACTER_ID`
- [ ] T022 [US2] Enforce out-of-state character validation in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` returning `INVALID_SESSION_STATE` and terminating connection if in-world packet received with unselected character (`session.getCharacterId() == 0L`)
- [ ] T023 [US2] Update `JoinWorldRequest` call sites in `platform-modulith/src/test/java/com/example/GatewayRoutingIT.java` and `platform-modulith/src/test/java/com/example/GatewaySecurityIT.java`

**Checkpoint**: User Stories 1 AND 2 both work independently — character identities are strictly authoritative with zero spoofing vectors.

---

## Phase 5: User Story 3 - Synchronized Message Catalog and Zero-Drift Communication (Priority: P2)

**Goal**: Guarantee unified, drift-free binary serialization schemas across client and server with automated wire ID collision prevention and complete round-trip recognition.

**Independent Test**: Run automated registry validation and round-trip serialization tests to verify 100% of defined protocol message types encode and decode identically between client and backend codecs with zero collisions.

### Tests for User Story 3

- [ ] T024 [P] [US3] Create automated wire registry collision and completeness unit test in `common-protocol/src/test/java/com/example/serialization/ForyRegistryTest.java`
- [ ] T025 [P] [US3] Add end-to-end round-trip serialization test in `platform-modulith/src/test/java/com/example/serialization/ForySyncIT.java`

### Implementation for User Story 3

- [ ] T026 [US3] Align all message ID constant references in `client-sdk/src/main/java/com/example/client/QuicClient.java` (including `PING = 50`, `PONG = 51`, `ZONE_STATE_DELTA_BROADCAST = 404`)
- [ ] T027 [US3] Align all message ID constant references in `platform-modulith/src/main/java/com/example/server/codec/` and domain handlers to unified `MessageType` constants
- [ ] T028 [US3] Enforce strict registration checks (`requireClassRegistration(true)`) in `platform-modulith/src/main/java/com/example/server/codec/BackendForySerializationProvider.java`

**Checkpoint**: All user stories are independently functional with zero protocol drift across modules.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Architecture conformance, resource leak validation, and quickstart scenario verification.

- [ ] T029 [P] Execute ArchUnit verification in `platform-modulith/src/test/java/com/example/ArchitectureTest.java` to verify modular boundaries and zero architecture violations
- [ ] T030 Execute test suite with Netty leak detection enabled via `./mvnw clean test -Dio.netty.leakDetection.level=PARANOID`
- [ ] T031 Execute and validate all scenarios in `specs/001-protocol-session-integrity/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can proceed in priority order (`US1` → `US2` → `US3`) or in parallel across work streams
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — delivers Gateway error signaling & defensive severing.
- **User Story 2 (P1)**: Can start after Foundational (Phase 2) — integrates with US1's out-of-state defensive severing in `GatewayInboundStreamHandler`.
- **User Story 3 (P2)**: Can start after Foundational (Phase 2) — provides formal collision guardrails and full round-trip verification across all unified wire types.

### Within Each User Story

- Tests MUST be written and fail before implementation
- DTOs and models before service handlers
- Handlers before gateway routing adjustments
- Core implementation before integration verification
- Story complete before moving to next priority

### Parallel Opportunities

- Within Phase 2: `T002` and `T003` can execute in parallel
- Within Phase 3 (US1): Tests `T006`, `T007`, `T008` can be written in parallel; Client SDK tasks `T009` and `T010` can execute in parallel
- Within Phase 4 (US2): Tests `T018` and `T019` can be written in parallel
- Within Phase 5 (US3): Tests `T024` and `T025` can run in parallel
- Within Phase 6: `T029` (ArchUnit) can run in parallel with general code cleanup

---

## Parallel Example: User Story 1

```bash
# Launch test authors for User Story 1 together:
Task: "Add test cases for unauthenticated packet rejection and channel closure in platform-modulith/src/test/java/com/example/GatewaySecurityIT.java"
Task: "Add test cases for rate-limit rejection and channel closure in platform-modulith/src/test/java/com/example/GatewaySecurityIT.java"
Task: "Add test cases for out-of-state packet injection rejection and channel closure in platform-modulith/src/test/java/com/example/GatewaySecurityIT.java"

# Launch Client SDK preparation in parallel:
Task: "Implement GatewayErrorResponseHandler in client-sdk/src/main/java/com/example/client/handler/GatewayErrorResponseHandler.java"
Task: "Add error tracking fields and state handling in client-sdk/src/main/java/com/example/client/state/ClientState.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (`T001`)
2. Complete Phase 2: Foundational (`T002` - `T005`)
3. Complete Phase 3: User Story 1 (`T006` - `T017`)
4. **STOP and VALIDATE**: Verify `GatewaySecurityIT` passes with structured rejection feedback and channel termination
5. Deliver MVP defensive perimeter

### Incremental Delivery

1. Setup + Foundational → Wire protocol and constants unified
2. Add User Story 1 → Gateway actively defends against bad actors (MVP!)
3. Add User Story 2 → Actor character context derived authoritatively from session
4. Add User Story 3 → Formal collision tests and zero-drift catalog verified
5. Run Polish Phase → ArchUnit and PARANOID leak detection passing

---

## Notes

- `[P]` tasks = different files, no shared write locks
- `[Story]` label maps task to specific user story for full traceability
- Every task includes an explicit file path
- Netty off-heap memory leak checks (`-Dio.netty.leakDetection.level=PARANOID`) must pass across all test suites
