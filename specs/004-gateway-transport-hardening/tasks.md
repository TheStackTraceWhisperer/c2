## Phase 1: Setup

**Purpose**: Project initialization and basic structure

*(No setup tasks required for this feature)*

---

## Phase 2: Foundational

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

*(No foundational tasks required for this feature)*

---

## Phase 3: User Story 1 - Stable Long-Running Sessions Under Rate-Limit Pressure (Priority: P1)

**Goal**: Ensure over-quota packets are cleanly discarded and do not leak memory.

- [x] T001 [US1] Remove conditional `refCnt() > 0` check from `finally` block in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` and unconditionally release `msg.payload()`
- [x] T002 [US1] Update `sendErrorAndSever` in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` to `retain()` error payload before `writeAndFlush()` and unconditionally `release()` in failure listener
- [x] T003 [US1] Update `sendError` in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` to `retain()` error payload before `writeAndFlush()` and unconditionally `release()` in failure listener

---

## Phase 4: User Story 2 - Reliable Message Delivery During Backend Connection Establishment (Priority: P1)

**Goal**: Ensure queued messages are cleanly discarded if backend connection fails.

- [x] T004 [US2] Update `channelRead0` in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` to `retain()` `backendEnvelope.payload()` before `writeAndFlush()` and unconditionally `release()` in failure listener
- [x] T005 [US2] Update `enqueueAndConnect` queue drain loop in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` to `retain()` before `writeAndFlush()` and unconditionally `release()` in failure listener
- [x] T006 [US2] Update `drainAndRelease` in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` to unconditionally `release()` without `refCnt()` check

---

## Phase 5: User Story 6 - Deterministic Rejection of Malformed and Poisoned Inbound Frames (Priority: P1)

**Goal**: Safely reject all malformed frames and close the QUIC stream without leaking memory.

- [x] T007 [US6] Update `exceptionCaught` in `platform-modulith/src/main/java/com/example/gateway/GatewayInboundStreamHandler.java` to intercept `IndexOutOfBoundsException` and `IllegalArgumentException` in addition to existing frame exceptions

---

## Phase 6: User Story 3 - Clean Session Teardown on Client Disconnect (Priority: P2)

**Goal**: Ensure disconnect notification buffers are unconditionally released.

- [x] T008 [P] [US3] Update `handleSessionDisconnect` in `platform-modulith/src/main/java/com/example/gateway/QuicGateway.java` to `retain()` notification payload before `writeAndFlush()` and unconditionally `release()` in failure listener

---

## Phase 7: User Story 4 - Zero Data Leakage Between Sessions on the Relay Path (Priority: P2)

**Goal**: Ensure backend responses are properly released when relayed or dropped.

- [x] T009 [P] [US4] Update blind pass-through in `platform-modulith/src/main/java/com/example/gateway/GatewayBackendRelayHandler.java` to `retain()` before `writeAndFlush()` and unconditionally `release()` in failure listener
- [x] T010 [P] [US4] Update control message parsing in `platform-modulith/src/main/java/com/example/gateway/GatewayBackendRelayHandler.java` to unconditionally `release()` in `finally` block without `refCnt()` check

---

## Phase 8: User Story 5 - Symmetric Backpressure Between Client and Backend Streams (Priority: P2)

**Goal**: Prevent unbounded in-memory buffering by implementing bidirectional auto-read toggling.

- [x] T011 [P] [US5] Implement writability-change listener on `clientStreamChannel` inside `platform-modulith/src/main/java/com/example/gateway/GatewayBackendRelayHandler.java` to resume/suspend auto-read on the backend stream

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [x] T012 Run leak detection tests in `platform-modulith/src/test/` to verify zero leaks across all scenarios
