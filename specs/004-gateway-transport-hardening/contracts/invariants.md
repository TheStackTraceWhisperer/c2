# Behaviour Contracts & Invariants

This document outlines the strict behavioral contracts and invariants that the Gateway Transport implementation must guarantee.

## 1. Buffer Lifecycle Invariants
- **Invariant 1**: Every `ByteBuf` carrying payload data must be explicitly released unless ownership is successfully transferred to the Netty write pipeline.
- **Invariant 2**: The canonical "retain-before-write" pattern MUST be strictly followed:
  - `retain()` must be invoked immediately prior to `writeAndFlush()`.
  - The associated `ChannelFutureListener` must `release()` the buffer if and only if `future.isSuccess()` evaluates to false.
- **Invariant 3**: Conditional reference count checks (e.g., `if (buf.refCnt() > 0)`) inside failure listeners are strictly forbidden due to race conditions.

## 2. Inbound Stream Disconnect / Abort Invariants
- **Invariant 4**: When an inbound stream is severed (e.g., due to rate-limit violation, unauthenticated access, or invalid state), the gateway must:
  1. Construct and send a structured `GatewayErrorResponse`.
  2. Close the stream upon the completion of the error write.
  3. Release the current message payload.
- **Invariant 5**: Any decoder exception (`TooLongFrameException`, `CorruptedFrameException`, etc.) MUST result in the immediate closure of the QUIC stream to discard all unread bytes and prevent lingering connections.

## 3. Backpressure Invariants
- **Invariant 6**: Auto-read synchronization must be strictly symmetric:
  - When the backend stream is non-writable, the inbound client stream's auto-read must be disabled. When writability returns, auto-read must be re-enabled.
  - When the client stream is non-writable, the inbound backend stream's auto-read must be disabled. When writability returns, auto-read must be re-enabled.

## 4. Leak-Free Guarantees
- **Invariant 7**: All execution paths MUST pass with zero leak reports under Netty's `PARANOID` leak detection level.
