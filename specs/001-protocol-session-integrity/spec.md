# Feature Specification: Protocol Synchronization, Session Identity Integrity, and Gateway Error Feedback

**Feature Branch**: `001-protocol-session-integrity`

**Created**: 2026-09-06

**Status**: Draft

**Input**: User description: "Remediate wire ID drift, eliminate hardcoded character context, and add Gateway error signaling"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Structured Rejection Feedback & Defensive Connection Termination (Priority: P1)

When a client violates security policies or the protocol state machine—such as submitting protected in-game requests while unauthenticated, sending in-world packets before selecting a character in the lobby, flooding requests beyond rate limits, or injecting malformed payloads—the gateway treats this as an active threat or exploit attempt. The gateway returns an explicit structured rejection frame and immediately terminates the transport connection to safeguard infrastructure and game integrity. The only condition under which a connection remains open upon request failure is when the fault is purely server-side (such as a backend service temporarily unavailable during a restart).

**Why this priority**: Eliminates silent message drops while actively defending gateway and downstream server resources from rogue clients, bot floods, packet injection, and state-machine exploit attempts.

**Independent Test**: Can be tested independently by submitting unauthenticated requests, out-of-state packets (in-world actions prior to character selection), or exceeding rate limits, and verifying that the client receives a structured rejection response followed by an immediate connection termination with zero leaked server resources.

**Acceptance Scenarios**:

1. **Given** a connected client without a verified authentication session, **When** they submit an in-game action or protected request, **Then** the gateway immediately returns an unauthenticated rejection response AND terminates the transport connection to prevent unauthorized probing.
2. **Given** a client that exceeds allocated request throughput limits, **When** the rate limit threshold is crossed, **Then** the gateway immediately returns a rate-limit rejection response AND severs the transport connection to halt packet flooding.
3. **Given** an authenticated client that has not completed character selection in the lobby, **When** they send an in-world action packet, **Then** the gateway treats this as an illegal protocol state transition / exploit attempt, returns an invalid state rejection response, AND immediately terminates the connection.
4. **Given** a request targeting an unroutable or temporarily unavailable backend destination due to internal server failure, **When** the gateway attempts to dispatch the packet, **Then** the client receives a service-unavailable rejection response while keeping the transport connection open to allow graceful client recovery.

---

### User Story 2 - Authoritative Session Identity & Untrusted Client Character Context (Priority: P1)

When an authenticated player performs in-world actions (such as moving, performing abilities, or interacting with the world), the simulation engine resolves the acting character identity strictly and authoritatively from the gateway's validated session context. The client is never trusted to self-report its own character identity, and client request contracts for self-actions must not accept a caller-supplied character ID. Any character identifier present in a client payload is strictly interpreted as a target, recipient, or operand (e.g., target character to inspect, trade with, or attack), never as the acting source.

**Why this priority**: Fundamental to multiplayer integrity and security; eliminates client-side spoofing, unauthorized character manipulation, identity confusion, and multi-user state corruption.

**Independent Test**: Can be tested independently by logging in two distinct accounts with different characters, submitting movement and action requests simultaneously from both, and verifying that world simulation attributes coordinates and actions exclusively to the respective session-derived characters, with zero reliance on client-asserted actor IDs.

**Acceptance Scenarios**:

1. **Given** Player 1 with Character A and Player 2 with Character B in the same game area, **When** Player 1 submits a movement update, **Then** the simulation engine updates only Character A's position, resolving the actor exclusively from Player 1's gateway session context.
2. **Given** a client request for an in-world player action, **When** transmitted to backend handlers, **Then** the backend handler derives the active character ID strictly from the gateway session envelope and ignores or rejects any client attempt to assert an actor ID.
3. **Given** a client request containing a character ID parameter for targeting or interaction (e.g., inspect, trade, combat target), **When** processed by the handler, **Then** the system treats that ID strictly as the passive recipient/target, while the acting identity is authoritatively bound to the caller's session context.
4. **Given** a session where the user switches characters in the lobby, **When** a subsequent in-world action is attempted, **Then** the system verifies the character selection matches the current world session before applying actions.

---

### User Story 3 - Synchronized Message Catalog and Zero-Drift Communication (Priority: P2)

As a developer and client user, all messages transmitted across the network adhere to a unified, authoritative protocol catalog so that every packet type is deterministically interpreted by both client and backend, preventing message collisions or unrecognized packet drops.

**Why this priority**: Prevents protocol drift where client and server disagree on message types, causing communication failures, corrupted payloads, and unexpected disconnects.

**Independent Test**: Can be tested independently by transmitting every defined packet type from client to server and server to client in a test suite, verifying 100% round-trip recognition without unknown type errors.

**Acceptance Scenarios**:

1. **Given** any recognized client-initiated packet, **When** transmitted across the gateway to backend services, **Then** the receiving service successfully decodes the message using the exact matching schema without registration mismatch.
2. **Given** a new message type added to the protocol, **When** both client and server build against the shared protocol definition, **Then** both sides automatically agree on the wire identifier without manual duplication.

---

### Edge Cases

- **Rapid Session Invalidation**: When a player's session is terminated while an in-flight request is traversing the gateway, the gateway catches the state change, responds with a session-expired rejection, and closes the connection.
- **Malformed Payload**: When an incoming packet has an unknown or unregistered wire identifier, the gateway immediately returns a malformed packet rejection response, severs the connection immediately, and safely releases all transport buffers.
- **Rate-Limit Flooding**: When a client breaches the rate limit, the gateway terminates the connection on the initial violating frame; any subsequent in-flight frames from that connection are discarded with buffers immediately reclaimed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The gateway MUST return an explicit structured error response to the client whenever an incoming request cannot be routed or processed.
- **FR-002**: Structured error responses MUST include a categorized error code (e.g., `UNAUTHENTICATED`, `RATE_LIMITED`, `INVALID_SESSION_STATE`, `ROUTE_NOT_FOUND`, `MALFORMED_PAYLOAD`) and a human-readable message.
- **FR-003**: The gateway MUST immediately terminate the client's transport connection upon detecting policy and security violations—specifically unauthenticated requests to protected endpoints, illegal out-of-state protocol actions (such as in-world requests without an active character), rate limit exhaustion, and malformed wire payloads—to preserve infrastructural stability.
- **FR-004**: The gateway MUST NOT silently drop client messages without returning error feedback, except when the physical transport connection has already terminated.
- **FR-005**: All game world and zone action handlers MUST derive the player's account identity and active acting character identity exclusively from verified gateway session contexts.
- **FR-006**: Clients MUST NOT supply their own character ID to assert their acting identity. If a request payload contains a character ID, backend services MUST treat it strictly as a target, destination, or operand, and MUST NEVER trust it as the source/actor of the request.
- **FR-007**: Handlers MUST NOT accept or rely on unverified client-supplied character IDs or hardcoded identity fallbacks for the active player.
- **FR-008**: Message wire identifiers across the network protocol MUST be defined in a single unified registry shared by both client and server components.
- **FR-009**: The build and test verification pipeline MUST reject any conflicting or duplicate wire identifier registrations.

### Key Entities

- **Session Context**: The authoritative gateway session state containing verified account identifier, currently selected character identifier, assigned world, and assigned zone. This is the sole trusted source for the acting player's identity.
- **Error Response**: A standard protocol message returned to the client containing an error category code, an optional numeric error subcode, and an explanatory text description.
- **Protocol Message Registry**: The authoritative catalog mapping each message contract to a unique, immutable wire identifier across all client and backend components.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of policy and security violations (unauthenticated access, out-of-state packet injection, rate limit breach, malformed payloads) receive a structured error response and trigger immediate connection termination within 50 milliseconds of gateway evaluation.
- **SC-002**: Zero socket descriptor or buffer resource leaks occur during abrupt defensive connection severing under adverse testing scenarios.
- **SC-003**: 100% of player movement and action requests across concurrent multi-user sessions correctly attribute coordinates and state to their respective verified characters without cross-session bleed, with zero reliance on client-asserted actor character IDs.
- **SC-004**: Zero protocol wire identifier collisions or drift between client and backend components during end-to-end communication tests.

## Assumptions

- Connected clients are capable of receiving and decoding standard error response messages over the established network channel before the connection terminates.
- Authentication tokens are validated at the gateway boundary prior to forwarding requests to internal services.
- Rate limiting thresholds are configured per active session rather than per IP address to ensure fair sharing across NAT networks.
- Existing credential and account stores maintain unique player account and character records.

