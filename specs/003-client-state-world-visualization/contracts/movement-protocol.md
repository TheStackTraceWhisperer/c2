# Contract: Movement Network Protocol & Reconciliation

**Modules**: `client-desktop`, `client-sdk`, `common-protocol`, `platform-modulith`  
**Protocol Messages**: `PlayerMovementRequest` (0x0040), `PlayerMovementResponse` (0x0041)  

## 1. Protocol Messages

### 1.1 `PlayerMovementRequest` (Client -> Server)

- **Message ID**: `0x0040` (Service: `0x0004` ZONE)
- **Serialization**: Apache Fory binary format
- **Payload Schema**:
  ```java
  public record PlayerMovementRequest(
      float x, float y, float z,
      float vx, float vy, float vz,
      float yaw, float pitch,
      int moveFlags,
      long clientTick
  ) implements PacketMessage
  ```
- **Movement Flags** (bitfield in `MovementFlags`):
  - `0x0000`: IDLE
  - `0x0001`: FORWARD
  - `0x0002`: BACKWARD
  - `0x0004`: STRAFE_LEFT
  - `0x0008`: STRAFE_RIGHT
  - `0x0010`: TURN_LEFT
  - `0x0020`: TURN_RIGHT

### 1.2 `PlayerMovementResponse` (Server -> Client)

- **Message ID**: `0x0041` (Service: `0x0004` ZONE)
- **Serialization**: Apache Fory binary format
- **Payload Schema**:
  ```java
  public record PlayerMovementResponse(
      long characterId,
      float x, float y, float z,
      float vx, float vy, float vz,
      float yaw, float pitch,
      int moveFlags,
      long serverTick,
      long clientTick,
      boolean reconciled
  ) implements PacketMessage
  ```

---

## 2. Timing & Rate Limiting Contract

1. **Client Dispatch Cadence**:
   - Packets MUST NOT be dispatched faster than once every **50ms** (20 Hz) during continuous movement.
   - When the player stops moving (transition from moving to idle), a final movement packet with `moveFlags = IDLE` and zero velocity MUST be dispatched immediately to finalize server coordinates.
2. **Server Tick Rate**:
   - `ZoneSimulationEngine` processes inbound movement queues at **20 Hz** (50ms tick cadence).
3. **Reconciliation Rules**:
   - If `reconciled == true` or the Euclidean distance between client predicted coordinates and server response exceeds **2.0 units**:
     $$\sqrt{(X_{client} - X_{server})^2 + (Z_{client} - Z_{server})^2} > 2.0$$
     The client MUST smoothly snap or interpolate its local coordinates to the server authoritative position.
   - If the distance is $\le 2.0$ units and `reconciled == false`, client prediction continues smoothly without rubber-banding.
