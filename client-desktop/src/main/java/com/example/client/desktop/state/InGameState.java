package com.example.client.desktop.state;

import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.net.DesktopKeepAliveService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.desktop.ui.InGamePanel;
import com.example.client.desktop.ui.radar.WorldRadarPanel;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.PongMessage;
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
    }

    @Override
    public void onUpdate(float dt) {
        GL11.glClearColor(0.07f, 0.07f, 0.09f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        panel.updateKeepAlive(keepAlive.getStatus(), keepAlive.getLastRttMs());
        panel.render();
        radarPanel.render();
        debugLog.render();

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
        }
    }

    private void handlePongPacket(PongMessage pong) {
        keepAlive.handlePong(pong);
        debugLog.log(String.format("PONG received (RTT: %dms)", keepAlive.getLastRttMs()));
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
