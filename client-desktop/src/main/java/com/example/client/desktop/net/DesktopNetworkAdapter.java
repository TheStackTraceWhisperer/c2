package com.example.client.desktop.net;

import com.example.client.ClientCallback;
import com.example.client.ClientConfiguration;
import com.example.client.DefaultClientConfiguration;
import com.example.client.QuicClient;
import com.example.client.desktop.concurrent.TaskScheduler;
import com.example.client.desktop.properties.DesktopClientProperties;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.lobby.dto.*;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class DesktopNetworkAdapter implements ClientCallback {

    private final QuicClient quicClient;
    private final TaskScheduler taskScheduler;
    @Getter private final ClientState clientState;
    private final DesktopClientProperties properties;
    private final ClientConfiguration clientConfiguration;
    private final com.example.client.desktop.engine.services.state.ApplicationStateService stateService;

    @Setter
    private volatile Consumer<PongMessage> pongListener;

    @PostConstruct
    public void init() {
        registerPacketHandlers();
    }

    private void registerPacketHandlers() {
        quicClient.on(MessageType.LOGIN_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.CHARACTER_LIST_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.CREATE_CHARACTER_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.SELECT_CHARACTER_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.JOIN_WORLD_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.PONG, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.GATEWAY_ERROR_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.ZONE_STATE_DELTA_BROADCAST, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
    }

    @Override
    public void onPacketReceived(Object packet) {
        taskScheduler.runOnMainThread(() -> {
            switch (packet) {
                case LoginResponse resp -> onLoginResponse(resp);
                case CharacterListResponse resp -> onCharacterListResponse(resp);
                case CreateCharacterResponse resp -> onCharacterCreateResponse(resp);
                case SelectCharacterResponse resp -> onCharacterSelectResponse(resp);
                case JoinWorldResponse resp -> onJoinWorldResponse(resp);
                case PongMessage resp -> onPongResponse(resp);
                case GatewayErrorResponse err -> onErrorResponse(err);
                case ZoneStateDeltaMessage delta -> onZoneStateDelta(delta);
                case PlayerMovementResponse moveResp -> onPlayerMovementResponse(moveResp);
                default -> log.warn("Unhandled wire packet: {}", packet.getClass().getSimpleName());
            }
            stateService.handlePacket(packet);
        });
    }

    // --- Inbound Handlers ---

    public void onLoginResponse(LoginResponse resp) {
        if (resp.success()) {
            clientState.setAccountId(resp.accountId());
            clientState.setAuthToken(resp.token());
            clientState.setLastErrorMessage("");
            clientState.setConnectionState(ClientConnectionState.AUTHENTICATED);
            log.info("DesktopNetworkAdapter -> AUTHENTICATED accountId={}", resp.accountId());
            sendCharacterList(resp.accountId(), resp.token());
        } else {
            String msg = resp.errorMessage() != null ? resp.errorMessage() : "Authentication failed";
            clientState.setLastErrorMessage(msg);
            clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
            log.warn("DesktopNetworkAdapter -> AUTH_FAILED: {}", msg);
        }
    }

    public void onCharacterListResponse(CharacterListResponse resp) {
        clientState.updateCharacters(resp.characters());
        clientState.setLastErrorMessage("");
        if (clientState.getSelectedCharacterId() == null) {
            clientState.setConnectionState(ClientConnectionState.IN_LOBBY);
        }
        log.info("DesktopNetworkAdapter -> Received {} characters", clientState.getCharacters().size());
    }

    public void onCharacterCreateResponse(CreateCharacterResponse resp) {
        if (resp.success()) {
            clientState.setLastErrorMessage("");
            clientState.setConnectionState(ClientConnectionState.IN_LOBBY);
            log.info("DesktopNetworkAdapter -> Character created id={}", resp.characterId());
            sendCharacterList(clientState.getAccountId(), clientState.getAuthToken());
        } else {
            clientState.setLastErrorMessage(resp.errorMessage());
            log.warn("DesktopNetworkAdapter -> Character creation failed: {}", resp.errorMessage());
        }
    }

    public void onCharacterSelectResponse(SelectCharacterResponse resp) {
        if (resp.success()) {
            clientState.setSelectedCharacterId(resp.characterId());
            clientState.setLastErrorMessage("");
            clientState.setConnectionState(ClientConnectionState.IN_WORLD);
            log.info("DesktopNetworkAdapter -> Character selected id={}", resp.characterId());
        } else {
            clientState.setLastErrorMessage(resp.errorMessage());
            log.warn("DesktopNetworkAdapter -> Character selection failed: {}", resp.errorMessage());
        }
    }

    public void onJoinWorldResponse(JoinWorldResponse resp) {
        if (resp.success()) {
            clientState.setCurrentZoneId(resp.initialZoneId());
            clientState.setLastErrorMessage("");
            clientState.setConnectionState(ClientConnectionState.IN_ZONE);
            log.info("DesktopNetworkAdapter -> IN_ZONE zoneId={}", resp.initialZoneId());
        } else {
            clientState.setLastErrorMessage(resp.errorMessage());
            clientState.setConnectionState(ClientConnectionState.IN_WORLD);
            log.error("DesktopNetworkAdapter -> Join world failed: {}", resp.errorMessage());
        }
    }

    public void onPongResponse(PongMessage resp) {
        long latency = Math.max(0, System.currentTimeMillis() - resp.timestamp());
        clientState.updateLatency(latency);
        if (pongListener != null) {
            pongListener.accept(resp);
        }
    }

    public void onErrorResponse(GatewayErrorResponse err) {
        clientState.setLastErrorCode(err.errorCode());
        clientState.setLastErrorMessage(err.message());
        log.error("DesktopNetworkAdapter -> Gateway error code={}, msg={}", err.errorCode(), err.message());
        if (err.errorCode() != GatewayErrorResponse.ERROR_SERVICE_UNAVAILABLE) {
            clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
        }
    }

    public void onZoneStateDelta(ZoneStateDeltaMessage delta) {
        if (delta.spawns() != null) {
            delta.spawns().forEach(clientState::addOrUpdateRemoteEntity);
        }
        if (delta.updates() != null) {
            delta.updates().forEach(clientState::updateRemoteEntityMovement);
        }
        if (delta.despawns() != null) {
            delta.despawns().forEach(clientState::removeRemoteEntity);
        }
    }

    public void onPlayerMovementResponse(PlayerMovementResponse resp) {
        clientState.setLastServerTick(resp.serverTick());
    }

    // --- Outbound Network Operations ---

    public void connect() throws Exception {
        connect(properties.getDefaultServerHost(), properties.getDefaultServerPort());
    }

    public void connect(String host, int port) throws Exception {
        if (!quicClient.isConnected()) {
            if (clientConfiguration instanceof DefaultClientConfiguration dcc) {
                dcc.getGateway().setHostname(host);
                dcc.getGateway().setPort(port);
            }
            log.info("DesktopNetworkAdapter -> Connecting to {}:{}...", host, port);
            quicClient.connect().sync();
            log.info("DesktopNetworkAdapter -> Connected to gateway successfully.");
        }
    }

    public boolean isConnected() {
        return quicClient.isConnected();
    }

    public void sendLogin(String username, String password) {
        clientState.setConnectionState(ClientConnectionState.CONNECTING);
        clientState.setLastErrorMessage("");
        sendPacket(new LoginRequest(username, password));
    }

    public void sendCharacterList(long accountId, String authToken) {
        sendPacket(new CharacterListRequest(accountId, authToken));
    }

    public void sendCharacterCreate(String authToken, int worldId, String name,
                                    short raceId, short gender, short faceId,
                                    short hairId, short sizeId, short nationId, short mainJobId) {
        sendPacket(new CreateCharacterRequest(
                authToken, worldId, name, raceId, gender, faceId, hairId, sizeId, nationId, mainJobId
        ));
    }

    public void sendCharacterSelect(String authToken, long characterId) {
        sendPacket(new SelectCharacterRequest(authToken, characterId));
    }

    public void sendCharacterDelete(long characterId) {
        clientState.getCharacters().removeIf(c -> c.characterId() == characterId);
        if (clientState.getSelectedCharacterId() != null && clientState.getSelectedCharacterId() == characterId) {
            clientState.setSelectedCharacterId(null);
        }
        clientState.notifyListeners();
    }

    public void sendPlay(String authToken) {
        sendPacket(new JoinWorldRequest(authToken));
    }

    public void sendMovement(float x, float y, float z, float vx, float vy, float vz, float yaw, float pitch, int moveFlags, long clientTick) {
        sendPacket(new PlayerMovementRequest(x, y, z, vx, vy, vz, yaw, pitch, moveFlags, clientTick));
    }

    public void sendPing(long timestamp, String message) {
        sendPacket(new PingMessage(timestamp, message));
    }

    public void sendLogout() {
        if (quicClient.isConnected()) {
            try {
                quicClient.close();
            } catch (Exception e) {
                log.warn("DesktopNetworkAdapter -> Error closing quicClient on logout: {}", e.getMessage());
            }
        }
        clientState.reset();
    }

    private void sendPacket(PacketMessage message) {
        taskScheduler.submit(() -> {
            try {
                if (!quicClient.isConnected()) {
                    connect();
                }
                quicClient.send(message);
            } catch (Exception e) {
                log.error("DesktopNetworkAdapter -> Failed to send packet {}: {}", message.getClass().getSimpleName(), e.getMessage(), e);
                taskScheduler.runOnMainThread(() -> {
                    clientState.setLastErrorMessage("Network error: " + e.getMessage());
                    if (clientState.getConnectionState() == ClientConnectionState.CONNECTING) {
                        clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
                    }
                });
            }
            return null;
        });
    }
}
