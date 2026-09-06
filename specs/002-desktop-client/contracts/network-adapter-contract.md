# Contract: QuicClient Network Adapter

**Module**: `client-desktop`  
**Target Dependency**: `client-sdk` (`QuicClient`, `ClientCallback`)

---

## 1. Outbound Actions (UI -> Network Adapter -> QuicClient)

The `NetworkAdapter` provides asynchronous facade methods invoked by `ClientState` on user action, sending typed wire packets from `common-protocol`:

| Method Signature                                          | Inbound UI Trigger             | Payload Sent to Gateway            | Expected Response Wire Packet |
|:----------------------------------------------------------|:-------------------------------|:-----------------------------------|:------------------------------|
| `connect(host, port)`                                     | Boot / Login Submit            | Establishes QUIC channel           | Connected status              |
| `sendLogin(username, password)`                           | Login button clicked           | `LoginRequest`                     | `LoginResponse`               |
| `sendCharacterList(accountId, authToken)`                 | Character screen loaded        | `CharacterListRequest`             | `CharacterListResponse`       |
| `sendCharacterCreate(authToken, worldId, name, ...)`       | Create Character form submit   | `CreateCharacterRequest`           | `CreateCharacterResponse`     |
| `sendCharacterSelect(authToken, characterId)`             | Select character clicked       | `SelectCharacterRequest`           | `SelectCharacterResponse`     |
| `sendPlay(authToken)`                                     | Play button clicked            | `JoinWorldRequest`                 | `JoinWorldResponse`           |
| `sendPing(timestamp, message)`                            | Keepalive loop or "Ping Now"   | `PingMessage`                      | `PongMessage`                 |
| `sendLogout()`                                            | Sign Out / Logout button       | Connection Teardown                | Disconnected / Reset          |

---

## 2. Inbound Events (QuicClient -> Network Adapter -> TaskScheduler -> ClientState)

All inbound callbacks from `QuicClient` arrive on Netty event loop threads and MUST be dispatched via `TaskScheduler.runOnMainThread(Runnable)` before modifying `ClientState` or triggering state transitions:

```java
public void onPacketReceived(Object packet) {
    taskScheduler.runOnMainThread(() -> {
        switch (packet) {
            case LoginResponse resp -> clientState.onLoginResponse(resp);
            case CharacterListResponse resp -> clientState.onCharacterListResponse(resp);
            case CreateCharacterResponse resp -> clientState.onCharacterCreateResponse(resp);
            case SelectCharacterResponse resp -> clientState.onCharacterSelectResponse(resp);
            case JoinWorldResponse resp -> clientState.onJoinWorldResponse(resp);
            case PongMessage resp -> clientState.onPongResponse(resp);
            case GatewayErrorResponse err -> clientState.onErrorResponse(err);
            default -> log.warn("Unhandled wire packet: {}", packet.getClass().getSimpleName());
        }
    });
}
```
