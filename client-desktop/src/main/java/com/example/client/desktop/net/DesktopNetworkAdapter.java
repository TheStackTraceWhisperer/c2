package com.example.client.desktop.net;

import com.example.client.ClientCallback;
import com.example.client.ClientConfiguration;
import com.example.client.DefaultClientConfiguration;
import com.example.client.QuicClient;
import com.example.client.desktop.concurrent.TaskScheduler;
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
import com.example.login.dto.ReconnectResponse;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class DesktopNetworkAdapter implements ClientCallback {

    private final QuicClient quicClient;
    private final TaskScheduler taskScheduler;
    @Getter private final ClientState clientState;
    private final ClientConfiguration clientConfiguration;
    private final com.example.client.desktop.engine.services.state.ApplicationStateService stateService;

    private final AtomicLong sessionEpoch = new AtomicLong(0L);
    private volatile Thread outboundThread;
    private final ExecutorService outboundExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "desktop-network-outbound");
        t.setDaemon(true);
        outboundThread = t;
        return t;
    });

    @PostConstruct
    public void init() {
        registerPacketHandlers();
    }

    @PreDestroy
    public void shutdown() {
        outboundExecutor.shutdown();
        try {
            if (!outboundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                outboundExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            outboundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void registerPacketHandlers() {
        quicClient.on(MessageType.LOGIN_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.RECONNECT_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.CHARACTER_LIST_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.CREATE_CHARACTER_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.SELECT_CHARACTER_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.JOIN_WORLD_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.PONG, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.GATEWAY_ERROR_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.ZONE_STATE_DELTA_BROADCAST, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> onPacketReceived(packet));
        quicClient.on(MessageType.ZONE_TRANSFER_NOTIFICATION, (ctx, packet) -> onPacketReceived(packet));
    }

    @Override
    public void onPacketReceived(Object packet) {
        taskScheduler.runOnMainThread(() -> {
            switch (packet) {
                case LoginResponse resp -> onLoginResponse(resp);
                case ReconnectResponse resp -> onReconnectResponse(resp);
                case CharacterListResponse resp -> onCharacterListResponse(resp);
                case CreateCharacterResponse resp -> onCharacterCreateResponse(resp);
                case SelectCharacterResponse resp -> onCharacterSelectResponse(resp);
                case JoinWorldResponse resp -> onJoinWorldResponse(resp);
                case PongMessage resp -> onPongResponse(resp);
                case GatewayErrorResponse err -> onErrorResponse(err);
                case ZoneStateDeltaMessage delta -> onZoneStateDelta(delta);
                case PlayerMovementResponse moveResp -> onPlayerMovementResponse(moveResp);
                case ZoneTransferNotification transfer -> onZoneTransfer(transfer);
                default -> log.warn("Unhandled wire packet: {}", packet.getClass().getSimpleName());
            }
            stateService.handlePacket(packet);
        });
    }

    public void onZoneTransfer(ZoneTransferNotification transfer) {
        log.info("DesktopNetworkAdapter -> ZoneTransferNotification: targetZoneId={}, spawn=({}, {}, {})",
                transfer.targetZoneId(), transfer.spawnX(), transfer.spawnY(), transfer.spawnZ());
        clientState.setCurrentZoneId(transfer.targetZoneId());
        clientState.setX(transfer.spawnX());
        clientState.setY(transfer.spawnY());
        clientState.setZ(transfer.spawnZ());
        clientState.clearRemoteEntities();
    }

    // --- Inbound Handlers ---

    public void onLoginResponse(LoginResponse resp) {
        if (resp.success()) {
            clientState.setAccountId(resp.accountId());
            clientState.setAuthToken(resp.token());
            clientState.setLastErrorMessage("");
            clientState.setConnectionState(ClientConnectionState.AUTHENTICATED);
            log.info("DesktopNetworkAdapter -> AUTHENTICATED accountId={}", resp.accountId());
        } else {
            String msg = resp.errorMessage() != null ? resp.errorMessage() : "Authentication failed";
            clientState.setLastErrorMessage(msg);
            clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
            log.warn("DesktopNetworkAdapter -> AUTH_FAILED: {}", msg);
        }
    }

    public void onReconnectResponse(ReconnectResponse resp) {
        if (resp.success()) {
            clientState.setAccountId(resp.accountId());
            clientState.setLastErrorMessage("");
            if (resp.characterId() > 0L) {
                clientState.setSelectedCharacterId(resp.characterId());
                clientState.setConnectionState(ClientConnectionState.IN_WORLD);
            } else {
                clientState.setConnectionState(ClientConnectionState.AUTHENTICATED);
            }
            log.info("DesktopNetworkAdapter -> RECONNECT succeeded accountId={}, characterId={}",
                    resp.accountId(), resp.characterId());
        } else {
            String msg = resp.errorMessage() != null ? resp.errorMessage() : "Reconnection failed";
            clientState.setLastErrorMessage(msg);
            clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
            log.warn("DesktopNetworkAdapter -> RECONNECT rejected: {}", msg);
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
            clientState.resetZoneSession();
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
            clientState.clearRemoteEntities();
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
        var gateway = clientConfiguration.getGateway();
        connect(gateway.getHostname(), gateway.getPort());
    }

    public void connect(String host, int port) throws Exception {
        if (Thread.currentThread() == outboundThread) {
            connectInternal(host, port);
            return;
        }
        try {
            outboundExecutor.submit(() -> {
                connectInternal(host, port);
                return null;
            }).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(e.getCause());
        }
    }

    private void connectInternal(String host, int port) throws Exception {
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
        log.warn("DesktopNetworkAdapter -> Character deletion requested for id={}, but deletion is not yet supported by server", characterId);
        clientState.setLastErrorMessage("Character deletion is not yet supported by server");
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
        sessionEpoch.incrementAndGet();
        clientState.reset();
        if (outboundExecutor.isShutdown()) {
            return;
        }
        try {
            outboundExecutor.execute(() -> {
                if (quicClient.isConnected()) {
                    try {
                        quicClient.close();
                    } catch (Exception e) {
                        log.warn("DesktopNetworkAdapter -> Error closing quicClient on logout: {}", e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("DesktopNetworkAdapter -> Logout close skipped during shutdown");
        }
    }

    private void sendPacket(PacketMessage message) {
        if (outboundExecutor.isShutdown()) {
            log.warn("DesktopNetworkAdapter -> Ignoring {} after shutdown", message.getClass().getSimpleName());
            return;
        }
        final long epoch = sessionEpoch.get();
        try {
            outboundExecutor.execute(() -> {
                if (sessionEpoch.get() != epoch) {
                    log.debug("DesktopNetworkAdapter -> Dropping stale packet {} after logout", message.getClass().getSimpleName());
                    return;
                }
                try {
                    if (!quicClient.isConnected()) {
                        var gateway = clientConfiguration.getGateway();
                        connectInternal(gateway.getHostname(), gateway.getPort());
                    }
                    quicClient.send(message);
                } catch (Exception e) {
                    log.error("DesktopNetworkAdapter -> Failed to send packet {}: {}", message.getClass().getSimpleName(), e.getMessage(), e);
                    taskScheduler.runOnMainThread(() -> {
                        if (sessionEpoch.get() == epoch) {
                            clientState.setLastErrorMessage("Network error: " + e.getMessage());
                            if (clientState.getConnectionState() == ClientConnectionState.CONNECTING) {
                                clientState.setConnectionState(ClientConnectionState.DISCONNECTED);
                            }
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("DesktopNetworkAdapter -> Outbound executor rejected {}: {}", message.getClass().getSimpleName(), e.getMessage());
        }
    }
}
