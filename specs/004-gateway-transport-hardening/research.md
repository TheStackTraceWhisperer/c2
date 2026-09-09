# Research: Gateway Transport Hardening

## Overview
This document outlines the technical design decisions, rationales, and rejected alternatives for implementing the Gateway Transport Hardening feature (Spec 004).

## 1. Buffer Lifecycle Management (Retain-before-Write)
**Decision**: Adopt the canonical Netty "retain-before-write" pattern. Before invoking `writeAndFlush()` on any channel (backend or client), the payload buffer will be explicitly `retain()`ed. An attached `ChannelFutureListener` will unconditionally call `release()` if the write operation fails.

**Rationale**: Netty's pipeline implicitly calls `release()` when a write succeeds (e.g. at the head context). Attempting to use a conditional `refCnt() > 0` guard within a failure listener introduces a race condition against this implicit release. By explicitly retaining before the write, the write failure path owns one reference, and the pipeline owns the other, eliminating the race and double-release risk.

**Alternatives Rejected**:
- *Conditional release in failure listener*: Rejected due to inherent race conditions with Netty's implicit release on successful writes.

## 2. Inbound Frame Exception Handling
**Decision**: When `exceptionCaught` is triggered in the pipeline (e.g., due to `TooLongFrameException` or a decoder arithmetic/bounds error), the handler will immediately close the QUIC stream channel.

**Rationale**: Stream closure implicitly discards any pending data in the cumulation buffer maintained by `LengthFieldBasedFrameDecoder` without requiring a custom decoder implementation or explicitly draining the buffer. It securely and deterministically severs the broken stream, complying with security requirements against maliciously crafted frames.

**Alternatives Rejected**:
- *Custom decoder subclass to drain buffer*: Rejected as overly complex and unnecessary since closing the stream accomplishes the same goal safely.

## 3. Idle Stream Timeout Management
**Decision**: Rely on the underlying QUIC transport's `maxIdleTimeout` (configured to 30s) to prune dead or stalled connections (e.g., partial frames).

**Rationale**: The QUIC stack natively handles idle connection termination without requiring additional higher-level Netty pipeline components like `IdleStateHandler`. This simplifies the pipeline and leverages the native capabilities of the transport layer.

**Alternatives Rejected**:
- *Adding `IdleStateHandler`*: Rejected as redundant since QUIC provides transport-level timeouts.

## 4. Symmetric Backpressure Propagation
**Decision**: 
- **Client-to-Backend**: Monitored directly in `GatewayInboundStreamHandler.channelWritabilityChanged`. When the backend stream's write buffer becomes full, suspend auto-read on the inbound client stream.
- **Backend-to-Client**: A writability-change listener will be attached directly to the `clientStreamChannel` within `GatewayBackendRelayHandler`. When the client channel's writability toggles, the handler will enable or disable auto-read on the backend stream channel.

**Rationale**: Co-locating the backpressure logic within the respective bridging handlers (GatewayInboundStreamHandler and GatewayBackendRelayHandler) keeps the cross-stream signalling simple, localized, and directly bound to the lifecycle of the relay.

**Alternatives Rejected**:
- *Dedicated cross-component propagation handler*: Rejected as it introduces unnecessary abstraction and wiring complexity.

## 5. Write-Buffer Watermarks
**Decision**: Hardcode the `WriteBufferWaterMark` at 32 KiB (low) and 64 KiB (high) during pipeline initialization for both client and backend streams.

**Rationale**: The specified values fit within the expected workload profile. Not exposing them via `GatewayProperties` keeps configuration surface area small. If tuning is needed in the future, it can be extracted to properties then.

**Alternatives Rejected**:
- *Application properties configuration*: Rejected as it is not needed at this stage per spec.
