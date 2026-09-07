package com.example.client.desktop.state;

import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.net.ClientState;
import com.example.client.desktop.ui.CharacterPanel;
import com.example.client.desktop.ui.DebugLogPanel;
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
    private final BeanProvider<UnauthenticatedState> unauthProvider;
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
        clientState.sendCharacterList();
        debugLog.log("CHAR_SELECT_OK " + characterName + " zone=" + currentZoneId);
    }

    @Override
    public void onUpdate(float dt) {
        GL11.glClearColor(0.07f, 0.07f, 0.09f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        panel.render(clientState);
        debugLog.render();

        processIntents();
        panel.clearIntents();

        // Transition to InGameState when bound to world
        if (clientState.getPhase() == ClientState.AppPhase.IN_WORLD) {
            Integer zoneId = clientState.getCurrentZoneId();
            int finalZone = zoneId != null ? zoneId : currentZoneId;
            InGameState next = inGameProvider.get();
            next.init(accountId, characterId, characterName, finalZone, 5000L);
            stateService.changeState(() -> next);
        }
    }

    @Override
    public void onExit() {}

    private void processIntents() {
        if (panel.isRefreshRequested()) {
            clientState.sendCharacterList();
        }
        if (panel.isSignOutRequested()) {
            clientState.sendLogout();
            stateService.changeState(unauthProvider::get);
        }
        if (panel.getSelectCharacterId() != null) {
            clientState.sendCharacterSelect(panel.getSelectCharacterId());
        }
        if (panel.getDeleteCharacterId() != null) {
            clientState.sendCharacterDelete(panel.getDeleteCharacterId());
        }
        if (panel.isCreateSubmitted()) {
            clientState.sendCharacterCreate(
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
            clientState.sendPlay();
        }
    }
}
