package com.example.client.desktop.state;

import com.example.client.desktop.camera.Camera;
import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.window.WindowService;
import com.example.client.desktop.input.MovementInputHandler;
import com.example.client.desktop.net.DesktopKeepAliveService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.desktop.ui.InGamePanel;
import com.example.client.desktop.ui.radar.WorldRadarPanel;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MovementFlags;
import com.example.zone.dto.PlayerMovementResponse;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.GL11;

@Slf4j
@Prototype
@RequiredArgsConstructor
public class InGameState implements ApplicationState {

    private final InGamePanel panel;
    private final WorldRadarPanel radarPanel;
    private final DebugLogPanel debugLog;
    private final DesktopKeepAliveService keepAlive;
    private final ClientState clientState;
    private final DesktopNetworkAdapter networkAdapter;
    private final ApplicationStateService stateService;
    private final WindowService windowService;
    private final MovementInputHandler movementHandler;
    private final Camera camera;
    private final BeanProvider<UnauthenticatedState> unauthProvider;
    private final BeanProvider<AuthenticatedState> authProvider;
    private final BeanProvider<CharacterSelectedState> selectedProvider;

    private long accountId;
    private long characterId;
    private String characterName;
    private int currentZoneId;
    private long keepaliveIntervalMs;

    public void init(long accountId, long characterId, String characterName, int zoneId, long keepaliveIntervalMs) {
        this.accountId = accountId;
        this.characterId = characterId;
        this.characterName = characterName;
        this.currentZoneId = zoneId;
        this.keepaliveIntervalMs = keepaliveIntervalMs;

        panel.setContext(characterName, Long.toString(characterId));
        debugLog.log("Entered zone " + zoneId + " as " + characterName);
    }

    @Override
    public void onEnter() {
        networkAdapter.setPongListener(this::handlePongPacket);
        keepAlive.start(Long.toString(characterId), keepaliveIntervalMs);
        camera.setTarget(clientState.getX(), clientState.getY(), clientState.getZ());

        // Authoritative initial spawn sync: probe server if local position is default (0, 0, 0)
        if (clientState.getX() == 0.0f && clientState.getY() == 0.0f && clientState.getZ() == 0.0f) {
            networkAdapter.sendMovement(
                    0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f,
                    MovementFlags.IDLE,
                    0L
            );
        }
    }

    @Override
    public void onUpdate(float dt) {
        GL11.glClearColor(0.07f, 0.07f, 0.09f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        // 1. Process camera orientation and keyboard/mouse movement inputs
        long handle = windowService.getHandle();
        long now = System.currentTimeMillis();

        camera.setTarget(clientState.getX(), clientState.getY(), clientState.getZ());
        camera.update(handle, dt);

        // Radar zoom via PageUp / PageDown keys
        if (handle != 0L) {
            float zoomRate = 50.0f; // meters per second
            if (org.lwjgl.glfw.GLFW.glfwGetKey(handle, org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                radarPanel.getConfig().zoomSmooth(-zoomRate * dt);
            }
            if (org.lwjgl.glfw.GLFW.glfwGetKey(handle, org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                radarPanel.getConfig().zoomSmooth(zoomRate * dt);
            }
        }

        var moveResult = movementHandler.pollAndCompute(
                handle,
                camera,
                clientState.getX(),
                clientState.getY(),
                clientState.getZ(),
                clientState.getYaw(),
                clientState.getPitch(),
                dt,
                now
        );

        if (moveResult.isMoving()) {
            clientState.updatePosition(
                    moveResult.x(), moveResult.y(), moveResult.z(),
                    moveResult.yaw(), moveResult.pitch(),
                    clientState.getLastServerTick()
            );
            camera.setTarget(moveResult.x(), moveResult.y(), moveResult.z());
        }

        if (moveResult.shouldSendPacket()) {
            networkAdapter.sendMovement(
                    moveResult.x(), moveResult.y(), moveResult.z(),
                    moveResult.vx(), moveResult.vy(), moveResult.vz(),
                    moveResult.yaw(), moveResult.pitch(),
                    moveResult.moveFlags(),
                    movementHandler.getClientTickCounter()
            );
        }

        // 2. Render UI Panels
        panel.updateKeepAlive(keepAlive.getStatus(), keepAlive.getLastRttMs());
        panel.render();
        radarPanel.render();
        debugLog.render();

        // 3. User Intent Processing
        if (panel.isPingRequested()) {
            debugLog.log("PING sent");
            keepAlive.sendPing();
        }
        if (panel.isCharacterSelectRequested()) {
            doCharacterSelect();
        }
        if (panel.isLogoutRequested()) {
            doLogout();
        }
        panel.clearIntents();

        if (clientState.getConnectionState() == ClientConnectionState.DISCONNECTED) {
            stateService.changeState(unauthProvider::get);
        }
    }

    @Override
    public void onExit() {
        networkAdapter.setPongListener(null);
        keepAlive.stop();
    }

    @Override
    public void onHandlePacket(Object packet) {
        if (packet instanceof PongMessage pong) {
            handlePongPacket(pong);
        } else if (packet instanceof PlayerMovementResponse moveResp) {
            handleMovementResponse(moveResp);
        }
    }

    private void handlePongPacket(PongMessage pong) {
        keepAlive.handlePong(pong);
        debugLog.log(String.format("PONG received (RTT: %dms)", keepAlive.getLastRttMs()));
    }

    private void handleMovementResponse(PlayerMovementResponse moveResp) {
        float dx = clientState.getX() - moveResp.x();
        float dz = clientState.getZ() - moveResp.z();
        float dist = (float) Math.hypot(dx, dz);

        if (moveResp.reconciled() || dist > 2.0f) {
            log.warn("Authoritative position reconciliation (dist={}): correcting ({}, {}, {}) -> ({}, {}, {})",
                    dist, clientState.getX(), clientState.getY(), clientState.getZ(),
                    moveResp.x(), moveResp.y(), moveResp.z());
            clientState.updatePosition(
                    moveResp.x(), moveResp.y(), moveResp.z(),
                    moveResp.yaw(), moveResp.pitch(),
                    moveResp.serverTick()
            );
            camera.setTarget(moveResp.x(), moveResp.y(), moveResp.z());
            debugLog.log(String.format("Position reconciled with server (delta=%.2fm)", dist));
        }
    }

    private void doLogout() {
        keepAlive.stop();
        debugLog.log("Signing out...");
        networkAdapter.sendLogout();
        stateService.changeState(unauthProvider::get);
    }

    private void doCharacterSelect() {
        keepAlive.stop();
        clientState.setSelectedCharacterId(null);
        clientState.setConnectionState(ClientConnectionState.IN_LOBBY);
        debugLog.log("Returning to Character Selection...");
        AuthenticatedState next = authProvider.get();
        next.init(accountId);
        stateService.changeState(() -> next);
    }
}
