# Feature Specification: Gateway Transport Hardening

**Feature Branch**: `004-gateway-transport-hardening`

**Created**: 2026-09-07

**Status**: Draft

**Input**: User description: "Harden the gateway transport layer against buffer/memory leaks and potential data leakage. Specifically: (1) BackendConnectionManager.forwardToBackend has multiple execution paths not all of which call envelope.payload().release(), risking ByteBuf reference count leaks. (2) GatewayInboundStreamHandler.channelRead drops the inbound envelope buffer without properly updating the reference count when rate limiting is triggered. Additional concerns include any other execution paths in GatewayInboundStreamHandler, GatewayBackendRelayHandler, and QuicGateway.handleSessionDisconnect where ByteBuf reference counts may not be correctly managed — including partial write failures, connection teardown races, and pending queue drain paths. Additionally, the gateway transport currently lacks a proper symmetric backpressure mechanism: write-buffer watermarks must be configured on both client-facing and backend-facing stream channels, writability-state changes must propagate bidirectionally to suspend and resume auto-read on the corresponding bridged stream, and `channelWritabilityChanged` lifecycle hooks must be implemented wherever streams are bridged."

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Stable Long-Running Sessions Under Rate-Limit Pressure (Priority: P1)

An operator runs the gateway under production-like load where a subset of connected clients repeatedly exceed their send quota. Over the course of hours, the gateway process must not consume unbounded off-heap memory, must not produce leak detection warnings, and must not degrade throughput for well-behaved clients sharing the same host.

**Why this priority**: Rate-limit enforcement is a hot path triggered on every excess packet. An uncorrected buffer lifecycle error on this path causes off-heap memory to accumulate monotonically, eventually exhausting native memory and crashing the process. This is the highest-severity risk class for a network gateway.

**Independent Test**: Can be fully tested by generating a sustained stream of over-quota packets from a test client, then asserting that off-heap memory stabilises and that leak detection reports zero leaks after the session ends.

**Acceptance Scenarios**:

1. **Given** a connected client whose session has an active rate limit, **When** the client sends packets faster than the allowed rate, **Then** each over-quota packet is cleanly discarded, the connection is severed with a structured error response, and no unreleased buffer is retained in off-heap memory after the connection closes.
2. **Given** a gateway running with leak detection at its most sensitive level, **When** thousands of over-quota packets are processed over an extended period, **Then** the leak detector reports zero resource leaks across all sessions.
3. **Given** a mix of well-behaved and over-quota clients on the same host, **When** over-quota clients are severed, **Then** well-behaved clients experience no memory-induced degradation or throughput reduction.

---

### User Story 2 — Reliable Message Delivery During Backend Connection Establishment (Priority: P1)

When a client sends messages to a backend service that has not yet established its transport connection, those messages are queued and flushed once the connection opens. If the connection attempt fails, the queued messages must be cleanly discarded. Under no circumstances may queued payloads remain unreferenced in off-heap memory after the queue is drained.

**Why this priority**: Queue-drain paths during connection failure are exercised every time a backend is temporarily unavailable. An unreleased buffer on this path is permanent — there is no later opportunity to release it — causing memory to leak on every backend restart or network partition.

**Independent Test**: Can be fully tested by injecting a connection failure for a target backend after several messages have been enqueued, then asserting that all queued payloads are released and off-heap memory returns to its pre-enqueue baseline.

**Acceptance Scenarios**:

1. **Given** a client session with messages queued for a backend that is currently connecting, **When** the connection attempt fails, **Then** every queued message payload is released and off-heap memory returns to baseline with zero leak reports.
2. **Given** a client session with messages queued for a backend, **When** the client disconnects before the backend connection completes, **Then** all pending payloads are released upon client disconnect with no retained buffers.
3. **Given** a successful backend connection with a non-empty pending queue, **When** one or more queued write operations fail mid-flush, **Then** only the payloads for the failed writes are released immediately, and successful writes are consumed without double-release.

---

### User Story 3 — Clean Session Teardown on Client Disconnect (Priority: P2)

When a client disconnects — whether gracefully, abruptly, or due to an idle timeout — all resources associated with that session must be unconditionally released. This includes in-flight payloads not yet written to any backend stream, payloads awaiting transmission over partially-writable backend streams, and any disconnect notification buffers allocated during the teardown sequence.

**Why this priority**: Session teardown occurs for every disconnecting client. Leaking teardown buffers is lower severity than hot-path leaks but cumulative over time — under moderate churn the process will exhaust native memory.

**Independent Test**: Can be fully tested by establishing sessions with multiple active backend connections, then abruptly severing the client connection and asserting zero retained buffers for that session.

**Acceptance Scenarios**:

1. **Given** a client session with active backend streams, **When** the client disconnects, **Then** a disconnect notification is sent to each active backend stream, and the payload buffer for each notification is released after the write completes or fails.
2. **Given** a client session where some backend streams are not writable at the moment of disconnect, **Then** disconnect notification buffers are still released regardless of write outcome.
3. **Given** a client session with a pending queue for a connecting backend, **When** the client disconnects before the backend connection resolves, **Then** the pending queue is drained and all payload buffers are released.

---

### User Story 4 — Zero Data Leakage Between Sessions on the Relay Path (Priority: P2)

When the relay handler forwards a backend response to a client stream that has become inactive between the time the response was received and the time the write is attempted, the payload must be released without being forwarded to any other session or channel. No client must ever receive data intended for a different session.

**Why this priority**: Incorrect ownership transfers on the relay path could result in a payload being written to the wrong client channel if ownership is misassigned, constituting a data confidentiality breach. Even when no cross-session write occurs, an unreleased payload on the drop path constitutes a memory leak.

**Independent Test**: Can be fully tested by disconnecting the target client stream between a backend response arriving and the write being attempted, then asserting the payload was released without being forwarded and with no memory leak.

**Acceptance Scenarios**:

1. **Given** a backend response in transit to a client, **When** the client stream becomes inactive before the write is dispatched, **Then** the payload is released, the write is not attempted, and no data is forwarded to any other channel.
2. **Given** a backend response that is written to the correct client stream but the write fails, **Then** the payload is released immediately after the failed write and no data is forwarded elsewhere.
3. **Given** a successful relay write to the client stream, **Then** payload ownership is fully transferred to the outbound pipeline without double-release.

---

### User Story 5 — Symmetric Backpressure Between Client and Backend Streams (Priority: P2)

The gateway bridges two independent stream channels: an inbound client stream and an outbound backend stream. When either side becomes temporarily unable to accept new data — signalled by its write buffer reaching capacity — the other side must pause ingestion immediately to prevent unbounded in-memory buffering. When the congested side recovers, ingestion must resume. This backpressure symmetry must operate in both directions: client-to-backend and backend-to-client.

**Why this priority**: Without symmetric backpressure, a fast sender on one side can overwhelm the write buffer of the other side, causing unbounded memory accumulation independently of the buffer-leak defects addressed in Stories 1–4. Under sustained high load this will exhaust available memory just as reliably as a reference-count leak. The mechanism is also a prerequisite for safe operation of the pending-queue and relay paths under load.

**Independent Test**: Can be fully tested in two independent sub-scenarios: (A) throttle the backend stream's outgoing capacity, send bursts from the client side, and assert the client stream's auto-read is paused then resumed when the backend recovers; (B) throttle the client stream's outgoing capacity, receive bursts from the backend side, and assert the backend stream's auto-read is paused then resumed when the client stream recovers. Assertions must include zero payload loss and zero leaked buffers across both scenarios.

**Acceptance Scenarios**:

1. **Given** an active client-to-backend stream pair, **When** the backend stream's write buffer reaches its high-watermark, **Then** auto-read is suspended on the inbound client stream so no additional client data is accepted until the backend stream drains below its low-watermark.
2. **Given** auto-read has been suspended on the client stream due to backend backpressure, **When** the backend stream's write buffer drains below the low-watermark and writability is restored, **Then** auto-read is re-enabled on the client stream and message forwarding resumes normally.
3. **Given** an active backend-to-client relay pair, **When** the client stream's write buffer reaches its high-watermark, **Then** auto-read is suspended on the backend stream so no additional backend responses are accepted until the client stream drains.
4. **Given** auto-read has been suspended on the backend stream due to client-side backpressure, **When** the client stream's write buffer drains and writability is restored, **Then** auto-read is re-enabled on the backend stream and relay forwarding resumes normally.
5. **Given** both stream pairs are operating under simultaneous backpressure from both directions, **When** writability is restored on each side independently, **Then** each auto-read suspension is lifted independently without deadlock and without payload loss or leak on either side.
6. **Given** a backend stream that becomes non-writable and then a subsequent write fails, **Then** the associated payload is released exactly once in the failure callback, and auto-read state on the client stream is reconciled correctly.

---

### Edge Cases

- What happens when a client sends a message for a service that has no resolvable endpoint, and the session disconnects before the error response write completes?
- How does the system handle a backend stream being closed by the remote end concurrently with the gateway attempting to write queued payloads to it?
- What happens when the disconnect notification write to a backend stream fails because the backend stream is already closed at the time of disconnect broadcast?
- What happens when a `drainAndRelease` call races with a concurrent queue removal from the flush path during stream establishment?
- How does the system behave if a conditional reference-count guard check races with the pipeline's own implicit release, potentially triggering a double-release exception?
- How does the system behave when a client sends a length field value that is large enough to bypass the maximum-frame guard but small enough that the decoder enters a perpetual "waiting for more bytes" state?
- What happens when a malformed length field causes an internal arithmetic exception during frame decoding — is the channel left in a defined, closed state?

---

### User Story 6 — Deterministic Rejection of Malformed and Poisoned Inbound Frames (Priority: P1)

A connected client — whether due to a bug, a network corruption, or deliberate adversarial intent — may send a frame whose declared length is inconsistent with the actual data that follows. The gateway must detect these conditions immediately and respond deterministically: discard all currently buffered bytes for that connection, return a structured error response to the client if the connection is still viable, and close the connection cleanly. Under no circumstances should a malformed frame leave a channel in a half-open, indefinitely waiting, or resource-leaking state.

**Why this priority**: A channel stalled on a poisoned length field holds a connection descriptor, a session record, and a reader buffer indefinitely. An adversary can open hundreds of such connections to exhaust gateway resources without any further packets. An unhandled decoding exception can leave the pipeline in an undefined state, compounding the resource impact. This is a security and availability concern of equal severity to the rate-limiting defects.

**Independent Test**: Can be fully tested using a test client that (A) sends a frame with an artificially large length field below the maximum-frame threshold and then sends no further data, verifying the connection is eventually rejected cleanly; (B) sends a frame with a length field that triggers a decoder arithmetic error, verifying the exception is caught and the channel is closed; (C) sends a frame exceeding the maximum allowed length, verifying the decoder throws a structured exception, the channel closes, and a structured error response is received before closure.

**Acceptance Scenarios**:

1. **Given** a connected client that sends a frame with a declared length larger than any legitimate message but below the configured maximum-frame-length guard, **When** no further data arrives, **Then** the gateway detects the incomplete frame condition within its idle-timeout window, returns a structured error response, and closes the connection cleanly — no channel is left open indefinitely.
2. **Given** a connected client that sends a frame whose declared length value triggers a decoder arithmetic or bounds error, **When** the error is encountered, **Then** the gateway intercepts the exception, drains all currently readable bytes from the inbound buffer, returns a structured error response if the channel is still writable, and closes the connection — leaving no channel in an undefined state and no buffer unreleased.
3. **Given** a connected client that sends a frame whose length exceeds the configured maximum frame size, **When** the decoder detects the violation, **Then** the gateway receives a structured oversized-frame signal, returns a structured error response to the client identifying the violation, and immediately closes the connection cleanly with all inbound buffer bytes discarded.
4. **Given** any of the above malformed-frame scenarios, **Then** the gateway's leak detector reports zero unreleased buffers after the connection closes, and no lingering channel descriptor is held for that client.
5. **Given** a gateway receiving malformed frames from multiple concurrent clients, **When** each is rejected and closed, **Then** well-behaved concurrent clients are unaffected and continue receiving service normally.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The gateway MUST release every inbound client payload buffer on all execution paths through the inbound message handler, including rate-limit rejection, authentication rejection, session-state rejection, endpoint resolution failure, and successful forwarding, regardless of write outcome.
- **FR-002**: When a message payload is enqueued for a backend that is currently establishing its connection, the gateway MUST guarantee that the payload is released exactly once — either by the successful write pipeline on connection success, or by the drain-and-release path on connection failure or client disconnect.
- **FR-003**: When the gateway discards an inbound packet due to rate limiting and severs the connection, the discard operation MUST result in a net reference count of zero for the discarded payload before or at the moment the connection is closed.
- **FR-004**: When the relay handler determines that the destination client stream is inactive, the gateway MUST release the backend response payload immediately rather than forwarding it to any other channel.
- **FR-005**: When a write to a backend stream or client stream fails, the gateway MUST release the payload buffer associated with that write exactly once in the failure callback.
- **FR-006**: When the gateway broadcasts a session-disconnect notification to all active backend streams on client disconnect, the notification payload buffer allocated for each stream MUST be released after the write completes or fails, regardless of write outcome.
- **FR-007**: The gateway MUST NOT perform a double-release on any buffer; any conditional reference-count guards used to prevent double-release must not themselves introduce a race with the pipeline's own implicit release on write success.
- **FR-008**: When a pending message queue for a backend endpoint is drained due to connection failure, all payloads in that queue MUST be released before the drain operation returns.
- **FR-009**: All stream channels that bridge client traffic to backend services MUST have explicit write-buffer capacity limits configured, so that the runtime can accurately report writability state and signal congestion before native buffers are exhausted.
- **FR-010**: When a backend stream's write buffer becomes full (non-writable), the gateway MUST immediately suspend auto-read on the corresponding inbound client stream. When the backend stream's write buffer drains and writability is restored, the gateway MUST re-enable auto-read on the client stream. This suspension-and-resumption cycle MUST be repeatable without any loss or leak of payloads in transit.
- **FR-011**: When a client stream's write buffer becomes full (non-writable) during relay of backend responses, the gateway MUST immediately suspend auto-read on the corresponding backend stream. When the client stream's writability is restored, the gateway MUST re-enable auto-read on the backend stream. This suspension-and-resumption cycle MUST be repeatable without any loss or leak of payloads in transit.
- **FR-012**: The writability-state change notification hook MUST be implemented on every component that bridges a stream pair, so that congestion signals are acted upon without delay regardless of which direction the congestion originates from.
- **FR-013**: Gateway components MUST run cleanly with the highest-sensitivity resource-leak detection enabled, producing zero leak reports under all normal and error paths.
- **FR-014**: When an inbound frame's declared length field causes a decoder-level arithmetic or bounds error during processing, the gateway MUST catch the error, discard all currently readable bytes from the inbound buffer for that connection, return a structured error response to the client if the connection is still writable, and close the connection immediately — leaving no channel in an undefined or partially-read state.
- **FR-015**: When an inbound frame's declared length exceeds the configured maximum allowed frame size, the gateway MUST discard all currently readable inbound bytes for that connection, return a structured error response identifying the violation, and immediately close the connection cleanly.
- **FR-016**: No channel connection MUST be left in a half-open, indefinitely waiting, or resource-retaining state as a result of any malformed, truncated, or poisoned inbound frame; all buffer references associated with the malformed frame MUST be released before or at the point of connection closure.

### Key Entities

- **Inbound Client Payload**: A reference-counted buffer received from a connected client, carrying a service identifier and message identifier. Its lifecycle begins at decode and must end at either pipeline transfer (on successful forward) or explicit release (on rejection or failure path).
- **Backend Envelope Payload**: A reference-counted buffer wrapping the inbound payload for forwarding to a backend service, with session and character context attached. Ownership may transfer from the pending queue to the write pipeline; the queue drain path is responsible for release on failure.
- **Pending Message Queue**: An ordered queue of backend envelopes accumulated while a backend connection is being established. The queue is the sole owner of its envelopes until flushed to the write pipeline or drained on failure.
- **Session Disconnect Notification Buffer**: A reference-counted buffer allocated per active backend stream during client disconnect teardown, carrying the session-disconnected event payload. Each instance has an independent lifecycle tied to the write outcome of its respective stream.
- **Client Session**: A stateful record associating a client connection with its authentication state, character context, rate-limit quota, and the set of established backend streams. The session is the authoritative source for which backend streams must be notified on disconnect.
- **Write-Buffer Watermark**: A configured pair of capacity thresholds (high-watermark and low-watermark) that govern when a stream channel signals non-writable and writable states respectively. Both client-facing and backend-facing stream channels must have these configured to enable correct backpressure propagation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Under a sustained load of over-quota packets from test clients, the gateway's off-heap memory consumption stabilises within a fixed bound above its idle baseline and does not grow monotonically over any 10-minute observation window.
- **SC-002**: After any client session ends — regardless of cause (rate limit, authentication failure, graceful disconnect, abrupt disconnect, or idle timeout) — the gateway's leak detector reports zero unreleased buffers attributable to that session.
- **SC-003**: Under simulated backend-unavailability conditions where connection attempts fail repeatedly, the gateway's off-heap memory returns to its pre-connection-attempt baseline within 5 seconds of each failure, with zero leak reports.
- **SC-004**: The gateway successfully handles at least 10,000 client connect-and-disconnect cycles without measurable off-heap memory growth as measured before the first cycle and after the last cycle.
- **SC-005**: Zero cross-session data delivery events occur under any combination of disconnect timing and relay write sequencing, as verified by session-tagged payload audit logging in test scenarios.
- **SC-006**: Gateway integration tests that exercise all rejection paths (rate limit, authentication, session-state, endpoint unavailable) pass with zero resource leak reports when run with the most sensitive leak detection level enabled.
- **SC-007**: Backend-connection queue flush paths (both success and failure) complete with zero retained payloads, as verified by memory baseline comparison before enqueue and after drain.
- **SC-008**: Under sustained high-throughput conditions where one or both bridged streams become temporarily non-writable, auto-read suspend and resume cycles complete correctly in both the client-to-backend and backend-to-client directions, with off-heap memory remaining bounded throughout and zero payload loss or leak detected across any 5-minute test window.
- **SC-009**: When test clients send malformed frames — including frames with oversized length fields, frames that trigger decoder arithmetic errors, and frames with declared lengths that stall the decoder — each connection is closed cleanly within the configured idle-timeout window, no channel descriptors are retained afterwards, and the gateway's leak detector reports zero unreleased buffers for those connections.

## Assumptions

- The inbound client message handler uses the framework's automatic release mechanism for successfully-decoded messages, meaning the framework calls release once on the decoded object after the message-read method returns; the handler must account for this by not releasing the original decoded message's payload itself unless it has first retained it.
- The pending queue drain paths are always invoked on the same event loop thread as the connection listener callbacks, eliminating the need for external locking within drain operations.
- Write-failure listener callbacks are invoked on the channel's event loop thread, so reference-count operations within those callbacks are safe from concurrent modification by the pipeline.
- The disconnect notification broadcast on session teardown is initiated from the channel's inbound event loop and does not require synchronisation beyond the existing concurrent map iteration.
- Leak detection test execution is feasible without requiring a full cluster; unit and integration tests exercising the gateway pipeline in isolation are sufficient for verifying SC-002, SC-006, and SC-007.
- The fix scope is limited to the gateway transport layer components. Domain logic, serialization schemas, and the client SDK are out of scope for this feature.
- Correctness of buffer lifecycle within codec layers (encode/decode) is a pre-condition assumed to hold; this feature focuses exclusively on handler-level ownership management above the codec boundary.
