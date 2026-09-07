package com.example.client.desktop.state;

import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.ui.CharacterPanel;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.state.ClientState;
import com.example.lobby.dto.CharacterSummary;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.RequiredArgsConstructor;
import org.lwjgl.opengl.GL11;

@Prototype
@RequiredArgsConstructor
public class AuthenticatedState implements ApplicationState {

    private final CharacterPanel panel;
    private final DebugLogPanel debugLog;
    private final ApplicationStateService stateService;
    private final ClientState clientState;
    private final DesktopNetworkAdapter networkAdapter;
    private final BeanProvider<UnauthenticatedState> unauthProvider;
    private final BeanProvider<CharacterSelectedState> selectedProvider;

    private long accountId;

    public void init(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public void onEnter() {
        networkAdapter.sendCharacterList(accountId, clientState.getAuthToken());
    }

    @Override
    public void onUpdate(float dt) {
        GL11.glClearColor(0.07f, 0.07f, 0.09f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        panel.render(clientState);
        debugLog.render();

        processIntents();
        panel.clearIntents();

        // Transition when character selection completes
        Long selectedId = clientState.getSelectedCharacterId();
        if (selectedId != null) {
            CharacterSummary selectedChar = clientState.getCharacterList().stream()
                    .filter(c -> c.characterId() == selectedId)
                    .findFirst()
                    .orElse(null);

            if (selectedChar != null) {
                CharacterSelectedState next = selectedProvider.get();
                next.init(accountId, selectedChar.characterId(), selectedChar.name(), selectedChar.zoneId());
                stateService.changeState(() -> next);
            }
        }
    }

    @Override
    public void onExit() {}

    private void processIntents() {
        if (panel.isRefreshRequested()) {
            networkAdapter.sendCharacterList(accountId, clientState.getAuthToken());
        }
        if (panel.isSignOutRequested()) {
            debugLog.log("Signed out");
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
    }
}
