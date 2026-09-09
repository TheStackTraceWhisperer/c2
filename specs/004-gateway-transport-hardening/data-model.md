# Data Model: Gateway Transport Hardening

This feature focuses on transport-level buffer lifecycle and flow control, not domain data persistence. The key entities modified or introduced operate entirely in memory during the lifecycle of network connections.

## In-Memory Entities

### ClientSession (Updates)
- **Description**: Represents a connected client's state. While not modified in its core fields, its associated `BackendStreams` map requires careful lifecycle synchronization to ensure that pending buffers are correctly released when the session is closed.
- **Lifecycle**: Created upon QUIC channel establishment; terminated on graceful disconnect, idle timeout, or security/rate-limit violation.

### BackendEnvelope (Lifecycle Management)
- **Description**: Wraps inbound client payloads for forwarding.
- **Ownership Transfer**: 
  - Created by `GatewayInboundStreamHandler`.
  - Stored temporarily in a `Queue<BackendEnvelope>` within `pendingQueues` during backend connection establishment.
  - Ownership transferred to the backend Netty pipeline upon `writeAndFlush()`.
  - Discarded (and `release()`d) if connection establishment fails or write fails.

### Pending Queues Map
- **Description**: `Map<Long, Queue<BackendEnvelope>> pendingQueues`
- **Key**: Computed from `serviceId`, `host`, and `port` via `BackendConnectionManager.makeKey`.
- **Value**: A queue of envelopes awaiting connection establishment.
- **Constraints**: Envelopes in this queue *must* be drained and their payloads released if the connection attempt fails or the channel goes inactive.

## Transport Configuration Constants

### Write Buffer Watermarks
- **Low Watermark**: 32 KiB (`32 * 1024` bytes)
- **High Watermark**: 64 KiB (`64 * 1024` bytes)
- **Application**: Applied to both `QuicStreamChannel` instances (client-facing and backend-facing) to trigger `channelWritabilityChanged` events for symmetric backpressure.
