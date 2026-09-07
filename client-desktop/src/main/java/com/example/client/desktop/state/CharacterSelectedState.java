package com.example.client.desktop.state;

import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.ui.CharacterPanel;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.RequiredArgsConstructor;
import org.lwjgl.opengl.GL11;

@Prototype
@RequiredArgsConstructor
public class CharacterSelectedState implements ApplicationState {

    private final CharacterPanel panel;
    private final DebugLogPanel debugLog;
    private final ApplicationStateService stateService;
    private final ClientState clientState;
    private final DesktopNetworkAdapter networkAdapter;
    private final BeanProvider<UnauthenticatedState> unauthProvider;
    private final BeanProvider<AuthenticatedState> authProvider;
    private final BeanProvider<InGameState> inGameProvider;

    private long accountId;
    private long characterId;
    private String characterName;
    private int currentZoneId;

    public void init(long accountId, long characterId, String characterName, int currentZoneId) {
        this.accountId = accountId;
        this.characterId = characterId;
        this.characterName = characterName;
        this.currentZoneId = currentZoneId;
    }

    @Override
    public void onEnter() {
        debugLog.log("Character selected: " + characterName + " (ID=" + characterId + ", zone=" + currentZoneId + ")");
    }

    @Override
    public void onUpdate(float dt) {
        GL11.glClearColor(0.07f, 0.07f, 0.09f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        panel.render(clientState);
        debugLog.render();

        boolean deselect = panel.isDeselectRequested();
        processIntents();
        panel.clearIntents();

        if (deselect || clientState.getSelectedCharacterId() == null) {
            clientState.resetZoneSession();
            clientState.setSelectedCharacterId(null);
            clientState.setConnectionState(ClientConnectionState.IN_LOBBY);
            AuthenticatedState next = authProvider.get();
            next.init(accountId);
            stateService.changeState(() -> next);
            return;
        }

        Long selectedId = clientState.getSelectedCharacterId();
        if (selectedId != null && selectedId != this.characterId) {
            var selectedChar = clientState.getCharacterList().stream()
                    .filter(c -> c.characterId() == selectedId)
                    .findFirst()
                    .orElse(null);
            if (selectedChar != null) {
                this.characterId = selectedChar.characterId();
                this.characterName = selectedChar.name();
                this.currentZoneId = selectedChar.zoneId();
            }
        }

        // Transition to InGameState when bound to zone via JoinWorldResponse
        if (clientState.getConnectionState() == ClientConnectionState.IN_ZONE) {
            int finalZone = clientState.getCurrentZoneId() > 0 ? clientState.getCurrentZoneId() : currentZoneId;
            InGameState next = inGameProvider.get();
            next.init(accountId, characterId, characterName, finalZone, 5000L);
            stateService.changeState(() -> next);
        }
    }

    @Override
    public void onExit() {}

    private void processIntents() {
        if (panel.isRefreshRequested()) {
            networkAdapter.sendCharacterList(accountId, clientState.getAuthToken());
        }
        if (panel.isSignOutRequested()) {
            networkAdapter.sendLogout();
            stateService.changeState(unauthProvider::get);
        }
        if (panel.getSelectCharacterId() != null) {
            networkAdapter.sendCharacterSelect(clientState.getAuthToken(), panel.getSelectCharacterId());
        }
        if (panel.getDeleteCharacterId() != null) {
            networkAdapter.sendCharacterDelete(panel.getDeleteCharacterId());
        }
        if (panel.isCreateSubmitted()) {
            int worldId = clientState.getCurrentWorldId() > 0 ? clientState.getCurrentWorldId() : 1;
            networkAdapter.sendCharacterCreate(
                    clientState.getAuthToken(),
                    worldId,
                    panel.getNewName(),
                    panel.getRaceId(),
                    panel.getGender(),
                    panel.getFaceId(),
                    panel.getHairId(),
                    panel.getSizeId(),
                    panel.getNationId(),
                    panel.getJobId()
            );
            panel.hideCreateForm();
        }
        if (panel.isPlayRequested()) {
            debugLog.log("Entering world as " + characterName + " (Zone #" + currentZoneId + ")...");
            networkAdapter.sendPlay(clientState.getAuthToken());
        }
    }
}
