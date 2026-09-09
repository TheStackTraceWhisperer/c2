# Implementation Plan: Gateway Transport Hardening

**Branch**: `004-gateway-transport-hardening` | **Date**: 2026-09-08 | **Spec**: [specs/004-gateway-transport-hardening/spec.md](specs/004-gateway-transport-hardening/spec.md)

**Input**: Feature specification from `/specs/004-gateway-transport-hardening/spec.md`

## Summary

Harden the gateway transport layer against buffer/memory leaks and data leakage by strictly managing ByteBuf lifecycles (using retain-before-write), safely closing channels on exceptions, and implementing symmetric backpressure.

## Technical Context

**Language/Version**: Java 25 utilizing Virtual Threads

**Primary Dependencies**: Netty Incubator QUIC, Micronaut 5.1.0

**Storage**: N/A for gateway transport scope

**Testing**: JUnit integration tests with Netty leak detection (PARANOID level)

**Target Platform**: Linux server / Kubernetes

**Project Type**: Network Gateway

**Performance Goals**: Bounded off-heap memory, zero leaks under high load/malformed inputs, deterministic stream shutdown.

**Constraints**: Strict adherence to Netty ByteBuf reference counting; Virtual Thread carrier pinning defense.

**Scale/Scope**: High-throughput QUIC/UDP proxying handling thousands of concurrent multiplexed client sessions.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **II. Zero-Leak ByteBuf & Strict Transport Lifecycle**: PASS. We mandate explicit `retain()` before `writeAndFlush()` and unconditional `release()` on failure.
- **I. Modular Isolation & Clean Boundaries**: PASS. Fixes are isolated entirely to the gateway layer and handlers. No dependencies on domain services.
- **Error Signaling & Client Feedback**: PASS. Malformed frames and rate-limits explicitly return structured error frames before connection closure.

## Project Structure

### Documentation (this feature)

```text
specs/004-gateway-transport-hardening/
├── plan.md              
├── research.md          
├── data-model.md        
├── quickstart.md        
├── contracts/
│   └── invariants.md
└── tasks.md             
```

### Source Code (repository root)

```text
platform-modulith/
└── src/
    ├── main/java/com/example/gateway/
    │   ├── GatewayInboundStreamHandler.java
    │   ├── GatewayBackendRelayHandler.java
    │   ├── BackendConnectionManager.java
    │   └── QuicGateway.java
    └── test/java/com/example/gateway/
        └── (Integration Tests)
```

**Structure Decision**: Fixes will be applied directly to the existing `platform-modulith` gateway handlers. No structural changes are necessary.
