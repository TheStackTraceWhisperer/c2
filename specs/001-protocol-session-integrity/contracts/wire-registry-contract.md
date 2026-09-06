# Contract: Unified Wire Message Registry

## Architecture
All message wire identifiers and Fory registrations are declared in `common-protocol`. `platform-modulith` inherits and extends this registry rather than redefining duplicate entries.

## Unified ID Ranges

| Range | Service Domain | Capacity | Visibility | Management |
| :---: | :--- | :---: | :--- | :--- |
| `1 – 49` | Gateway Internal Control Plane | 49 IDs (32+ reserved) | Backend Only | `BackendForySerializationProvider` |
| `50 – 99` | System Diagnostics & Health | 50 IDs | Shared | `ForySerializationProvider` |
| `100 – 199` | Authentication Service | 100 IDs | Shared | `ForySerializationProvider` |
| `200 – 299` | Character Lobby Service | 100 IDs | Shared | `ForySerializationProvider` |
| `300 – 399` | World / Realm Service | 100 IDs | Shared | `ForySerializationProvider` |
| `400 – 499` | Zone Simulation Service | 100 IDs | Shared | `ForySerializationProvider` |

### System Diagnostics & Health Assignments (`50 – 99`)
- `50`: `PingMessage` (Client ↔ Server heartbeat)
- `51`: `PongMessage` (Server ↔ Client heartbeat response)
- `52`: `GatewayErrorResponse` (Edge Gateway structured rejection frame)
- `99`: `SessionDisconnectedEvent` (Gateway → Backend session cleanup event)

## Registry Inheritance Model

```text
[common-protocol]
ForySerializationProvider
  └── Registers IDs 50..499 (Shared DTOs & System Diagnostics)
  └── Exposes: registerCommon(ForyBuilder builder)

[platform-modulith]
BackendForySerializationProvider
  └── Calls: ForySerializationProvider.registerCommon(builder)
  └── Registers IDs 1..49 (Internal Control Messages) and 99 (SessionDisconnectedEvent)
  └── Enables: requireClassRegistration(true)
```

## Guardrail Tests
- An automated unit test in `common-protocol` asserts that no two registered classes share the same wire ID.
- An ArchUnit or integration test in `platform-modulith` asserts that every shared DTO in `common-protocol` is recognized by `BackendForySerializationProvider` without manual synchronization.
