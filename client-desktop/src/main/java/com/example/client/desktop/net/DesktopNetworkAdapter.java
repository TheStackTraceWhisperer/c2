package com.example.client.desktop.net;

import com.example.client.ClientCallback;
import com.example.client.QuicClient;
import com.example.client.desktop.concurrent.TaskScheduler;
import com.example.client.desktop.properties.DesktopClientProperties;
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
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.client.ClientConfiguration;
import com.example.client.DefaultClientConfiguration;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class DesktopNetworkAdapter implements ClientCallback {

    private final QuicClient quicClient;
    private final TaskScheduler taskScheduler;
    private final ClientState clientState;
    private final DesktopClientProperties properties;
    private final ClientConfiguration clientConfiguration;
    private final com.example.client.desktop.engine.services.state.ApplicationStateService stateService;

    @PostConstruct
    public void init() {
        clientState.setNetworkAdapter(this);
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
    }

    @Override
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
            stateService.handlePacket(packet);
        });
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

    public void sendPlay(String authToken) {
        sendPacket(new JoinWorldRequest(authToken));
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
                    if (clientState.getPhase() == ClientState.AppPhase.AUTHENTICATING) {
                        clientState.setPhase(ClientState.AppPhase.DISCONNECTED);
                    }
                });
            }
            return null;
        });
    }
}
