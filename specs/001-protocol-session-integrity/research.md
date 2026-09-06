# Research & Technical Decisions: Protocol Synchronization, Session Identity Integrity, and Gateway Error Feedback

## Technical Unknowns & Findings

### 1. Unified Wire Registry & Serialization Provider
- **Decision**: Centralize all wire message ID constants and base Apache Fory serialization mappings in `common-protocol`. Refactor `BackendForySerializationProvider` in `platform-modulith` to inherit or delegate to `ForySerializationProvider`, reserving IDs `1–49` for backend-internal control messages (providing 32+ reserved slots for multi-backend expansion) and ID `99` for session disconnection events.
- **Rationale**: 
  - Having dual static lists in `common-protocol` and `platform-modulith` introduces runtime deserialization errors if wire IDs diverge or if new DTOs are registered in one module but forgotten in the other.
  - Making `common-protocol` the sole authority for all public wire DTOs guarantees compile-time and runtime synchronization between `client-sdk` and `platform-modulith`.
- **Alternatives Considered**:
  - *Automated reflection-based test comparing registries*: Validates synchronization during test execution, but fails to prevent developer drift during rapid local iteration.
  - *Unified single enum with reflection registration*: Adds reflective startup overhead; Apache Fory static registration is zero-cost and fast.

### 2. Authoritative Character Context Resolution
- **Decision**:
  - Remove caller-asserted actor character IDs from client requests.
  - Sourcing across network boundaries: The Edge Gateway already populates `BackendEnvelope.characterId()` from `session.getCharacterId()`. `BackendEnvelopeCodec` writes this 8-byte field into the wire header across the QUIC network stream to isolated backend pods. On the backend pod, `ServerEnvelopePayloadDecoder` decodes the header and sets `ctx.channel().attr(SessionContext.CHARACTER_ID)`.
  - In `JoinWorldRequest`, the client no longer supplies `characterId`. `JoinWorldRequestHandler` on the World Server pod reads `ctx.channel().attr(SessionContext.CHARACTER_ID).get()`, eliminating any reliance on client payload parameters.
  - In `GatewayInboundStreamHandler`, if an incoming request targets `ServiceType.WORLD` or `ServiceType.ZONE` while `session.getCharacterId() == 0L`, the gateway rejects the request as an out-of-state protocol violation and immediately terminates the connection.
  - Any future character ID field in payloads (e.g. inspection, trade, target) is explicitly named `targetCharacterId` or `recipientCharacterId` and validated as an operand.
- **Rationale**:
  - The client UI state machine dictates that in-world actions cannot legitimately occur before character selection. Receiving in-world actions while in lobby state is a protocol exploit attempt.
  - Sourcing actor identity strictly from the gateway session context prevents impersonation and character hijacking.
- **Alternatives Considered**:
  - *Validating client character ID against session ID*: Sending the ID redundantly wastes bandwidth and introduces ambiguity over whether the payload or session is authoritative.
  - *Allowing out-of-state packets with a polite rejection*: Rejected because sending in-world packets from the lobby is a signature of packet injection or modified client binaries.

### 3. Gateway Error Signaling & Defensive Connection Severing
- **Decision**:
  - Create a new DTO `GatewayErrorResponse` in `common-protocol` with wire ID `52` (System / Diagnostics range, alongside Ping=`50`, Pong=`51`):
    - `short errorCode` (e.g., `1001: UNAUTHENTICATED`, `1002: RATE_LIMITED`, `1003: INVALID_SESSION_STATE`, `1004: ROUTE_UNAVAILABLE`, `1005: MALFORMED_PAYLOAD`)
    - `short rejectedServiceId`
    - `short rejectedMessageId`
    - `String message`
  - In `GatewayInboundStreamHandler`:
    - For security / state machine violations (`RATE_LIMITED`, `UNAUTHENTICATED`, `INVALID_SESSION_STATE`, `MALFORMED_PAYLOAD`): Encode and write `GatewayErrorResponse`, then immediately close the stream and parent QUIC channel via `.addListener(f -> clientStream.parent().close())`.
    - For server-side failures (`ROUTE_UNAVAILABLE`): Encode and write `GatewayErrorResponse` but leave connection open to allow client retry.
    - All buffer allocations (`msg.payload()`) must be reliably released in `finally` blocks to guarantee zero off-heap leaks.
- **Rationale**:
  - Replaces silent drops with clear error signaling for legitimate clients while instantly terminating connections from malicious or malfunctioning clients.
  - Enforces Netty `ByteBuf` release guarantees under error conditions.
- **Alternatives Considered**:
  - *Generic Netty exceptions*: Quic exceptions close streams with error codes, but application-level structured DTOs allow client UI engines (e.g. Godot, Unreal, or test drivers) to display localized user-facing alerts.

