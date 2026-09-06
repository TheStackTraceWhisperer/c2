# Implementation Plan: Protocol Synchronization, Session Identity Integrity, and Gateway Error Feedback

**Branch**: `001-protocol-session-integrity` | **Date**: 2026-09-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-protocol-session-integrity/spec.md`

## Summary

This feature eliminates critical protocol and security technical debt across the platform:
1. **Unified Wire Registry**: Eliminates dual registration drift between `common-protocol` and `platform-modulith` by making `ForySerializationProvider` the single source of truth for wire type IDs, with backend providers inheriting shared definitions.
2. **Authoritative Session Identity**: Enforces that actor character identity is resolved strictly from the authoritative Gateway session context. The Edge Gateway already injects `session.getCharacterId()` into the binary header of every `BackendEnvelope`, which `BackendEnvelopeCodec` transmits across the network to isolated backend pods. On the backend pod, `ServerEnvelopePayloadDecoder` sets this into `ctx.channel().attr(SessionContext.CHARACTER_ID)`. Backend handlers (such as `JoinWorldRequestHandler`) will be refactored to read exclusively from `SessionContext.CHARACTER_ID` instead of trusting client payload parameters. Client request contracts (e.g. `JoinWorldRequest`) have caller-asserted character IDs removed.
3. **Gateway Error Signaling & Defensive Severing**: Replaces silent packet drops in `GatewayInboundStreamHandler` with structured `GatewayErrorResponse` frames. Implements immediate transport connection termination on policy/security violations (unauthenticated access, out-of-state packet injection, rate limit breaches, malformed frames) while preserving connections for transient server-side errors.

## Technical Context

**Language/Version**: Java 25 (utilizing Virtual Threads for blocking tasks)

**Primary Dependencies**: 
- Netty Incubator QUIC (`io.netty.incubator:netty-incubator-codec-classes-quic:0.0.75.Final`)
- Apache Fory (`org.apache.fory:fory-core:1.6.0`)
- Micronaut 5.1.0 (`io.micronaut:micronaut-runtime`)
- Nimbus JOSE JWT (`com.nimbusds:nimbus-jose-jwt:9.48`)
- ArchUnit (`com.tngtech.archunit:archunit-junit5:1.4.1`)

**Storage**: PostgreSQL with schema-level isolation (`game`, `player`, `infra`) via JDBC repositories

**Testing**: JUnit 5, MicronautTest, ArchUnit, AssertJ, Awaitility, Testcontainers

**Target Platform**: Linux / x86_64 containerized on Kubernetes (k3d cluster harness)

**Project Type**: Multi-module Maven Modulith (`common-protocol`, `client-sdk`, `platform-modulith`)

**Performance Goals**: Sub-50ms rejection feedback and connection severing on policy violations; zero off-heap `ByteBuf` leaks under high-throughput adversarial packet floods

**Constraints**: Strict compliance with Project Constitution v1.1.0; no circular module dependencies; ArchUnit verification on every build

**Scale/Scope**: Affects Gateway ingress pipeline, serialization codecs across all 3 modules, and session resolution in World/Zone handlers

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle / Standard | Status | Assessment & Guarantees |
| :--- | :---: | :--- |
| **I. Modular Isolation & Clean Boundaries** | **PASS** | `common-protocol` remains pure (DTOs, error codes, serialization). Edge Gateway only routes, validates tokens, rate-limits, and relays packets; it touches no domain handlers. Server never depends on `client-sdk`. Verified by [`ArchitectureTest`](file:///home/samuel/projects/netty-quic-gateway-demo/platform-modulith/src/test/java/com/example/ArchitectureTest.java). |
| **II. Zero-Leak ByteBuf & Strict Transport Lifecycle** | **PASS** | All incoming `ClientEnvelope` payloads are explicitly released in `finally` blocks in `GatewayInboundStreamHandler`. Error response buffers are tracked and flushed with close listeners. Tests run with `-Dio.netty.leakDetection.level=PARANOID`. |
| **III. Deterministic Binary Serialization & Drift-Free Schemas** | **PASS** | Dual registration lists eliminated. `ForySerializationProvider` in `common-protocol` manages shared IDs 50..499. `BackendForySerializationProvider` inherits shared registrations and registers backend control plane IDs (1..49, reserving 32+ IDs for expansion) and SessionDisconnectedEvent (99). |
| **IV. Stateless Cryptographic Security & Context Resolution** | **PASS** | Resolves the active character strictly from verified session context; eliminates client-asserted actor character IDs in `JoinWorldRequest`. |
| **V. Deterministic Simulation & Zone Concurrency Model** | **PASS** | Zone simulation receives verified actor character ID via `SessionContext.CHARACTER_ID` from the gateway envelope. No blocking I/O on the zone tick thread. |
| **Error Signaling & Defensive Severing** | **PASS** | Replaces silent drops with `GatewayErrorResponse`. Closes transport connection immediately on security/rate-limit violations to defend server infrastructure. |

## Project Structure

### Documentation (this feature)

```text
specs/001-protocol-session-integrity/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 decisions & alternatives
├── data-model.md        # Phase 1 schemas & state machine
├── quickstart.md        # Phase 1 runnable validation scenarios
├── contracts/           # Phase 1 wire contracts
│   ├── gateway-error-contract.md
│   └── wire-registry-contract.md
├── checklists/
│   └── requirements.md  # Quality validation checklist
└── tasks.md             # Phase 2 output (/speckit-tasks command)
```

### Source Code (repository root)

```text
common-protocol/
└── src/main/java/com/example/
    ├── common/
    │   ├── codec/
    │   │   └── ForySerializationProvider.java   # Centralized wire IDs and common DTO registrations
    │   ├── dto/
    │   │   └── GatewayErrorResponse.java        # Standard structured error DTO (Wire ID 52)
    │   └── protocol/
    │       └── MessageType.java                 # GATEWAY_ERROR_RESPONSE = 52
    └── world/dto/
        └── JoinWorldRequest.java                # Refactored: removed caller-asserted characterId

client-sdk/
└── src/main/java/com/example/client/
    ├── handler/
    │   └── GatewayErrorResponseHandler.java     # Receives GatewayErrorResponse & transitions state
    └── state/
        └── ClientState.java                     # Records last gateway error code & reason

platform-modulith/
├── src/main/java/com/example/
    ├── gateway/
    │   └── GatewayInboundStreamHandler.java     # Emits error frames & defensively severs connection
    ├── server/codec/
    │   └── BackendForySerializationProvider.java# Inherits shared registrations from ForySerializationProvider
    └── world/handler/
        └── JoinWorldRequestHandler.java         # Resolves character strictly from SessionContext.CHARACTER_ID
└── src/test/java/com/example/
    ├── GatewaySecurityIT.java                   # Tests defensive termination on unauth & rate-limit
    ├── ArchitectureTest.java                    # Enforces module and boundary isolation rules
    └── serialization/
        └── ForyRegistryTest.java                # Verifies zero wire ID collisions and drift
```

**Structure Decision**: Multi-module modulith structure matching existing architecture: shared protocol schemas and DTOs in `common-protocol`, client event handlers in `client-sdk`, and gateway / server handlers in `platform-modulith`.

## Complexity Tracking

> **Constitution Compliance**: All gates passed with zero architectural violations. No complexity exemptions required.

