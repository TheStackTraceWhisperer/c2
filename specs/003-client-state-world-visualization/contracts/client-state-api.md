# Contract: Canonical ClientState API

**Module**: `client-sdk`  
**Package**: `com.example.client.state`  
**Class**: `ClientState`  

## 1. Purpose

Provides a thread-safe, reactive, single source of truth for all client-side state, including connection lifecycle, authentication credentials, character roster, local player spatial coordinates, latency telemetry, and remote player characters within the Area of Interest (AoI).

---

## 2. Interface Contract

### 2.1 Properties & Accessors

| Accessor | Return Type | Thread Safety | Description |
|---|---|---|---|
| `getConnectionState()` | `ClientConnectionState` | Lock-free (`volatile`) | Current connection/session state. |
| `getAccountId()` | `long` | Lock-free (`volatile`) | Authenticated account ID (0 if none). |
| `getAuthToken()` | `String` | Lock-free (`volatile`) | Signed JWT authentication token. |
| `getSelectedCharacterId()` | `Long` | Lock-free (`volatile`) | Currently active/selected character ID (`null` if unselected). |
| `getCurrentWorldId()` | `int` | Lock-free (`volatile`) | Connected world ID. |
| `getCurrentZoneId()` | `int` | Lock-free (`volatile`) | Active zone ID (e.g. 230). |
| `getX()`, `getY()`, `getZ()` | `float` | Lock-free (`volatile`) | Local player Cartesian position coordinates. |
| `getYaw()`, `getPitch()` | `float` | Lock-free (`volatile`) | Local player heading and pitch rotation. |
| `getLastServerTick()` | `long` | Lock-free (`volatile`) | Sequence number of latest server simulation tick received. |
| `getCurrentLatencyMs()` | `long` | Lock-free (`volatile`) | Current RTT latency in milliseconds. |
| `getLastErrorMessage()` | `String` | Lock-free (`volatile`) | Last error message received from network or gateway. |
| `getLastErrorCode()` | `short` | Lock-free (`volatile`) | Last numeric error code received. |
| `getCharacters()` | `List<CharacterSummary>` | Thread-safe snapshot (`CopyOnWriteArrayList`) | Unmodifiable view of character roster. |
| `getRemoteEntities()` | `Map<Long, RemoteEntityState>` | Concurrent map (`ConcurrentHashMap`) | Active remote player characters within AoI. |

### 2.2 Mutation Methods (Invoked by Network Handlers)

```java
// Entity Management
void addOrUpdateRemoteEntity(EntitySpawnRecord spawn);
void updateRemoteEntityMovement(EntityMovementUpdate update);
void removeRemoteEntity(long characterId);

// Local Position
void updatePosition(float x, float y, float z, long serverTick);
void updatePosition(float x, float y, float z, float yaw, float pitch, long serverTick);

// Roster & Telemetry
void updateCharacters(List<CharacterSummary> newCharacters);
void updateLatency(long latencyMs);

// Lifecycle
void reset();
```

### 2.3 Reactive Subscription Contract

```java
void onStateChange(Consumer<ClientState> listener);
void notifyListeners();
```
- Listeners MUST NOT perform blocking I/O or sleep inside the notification callback.
- Notifications may be triggered from Netty event loop threads or the main thread; listeners requiring UI mutation MUST dispatch updates to the main GLFW thread if not already executed there.
