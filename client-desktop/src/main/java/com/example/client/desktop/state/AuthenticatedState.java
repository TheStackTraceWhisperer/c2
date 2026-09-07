package com.example.client.desktop.state;

import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.net.ClientState;
import com.example.client.desktop.ui.CharacterPanel;
import com.example.client.desktop.ui.DebugLogPanel;
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
    private final BeanProvider<UnauthenticatedState> unauthProvider;
    private final BeanProvider<CharacterSelectedState> selectedProvider;

    private long accountId;

    public void init(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public void onEnter() {
        clientState.sendCharacterList();
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
            clientState.sendCharacterList();
        }
        if (panel.isSignOutRequested()) {
            debugLog.log("Signed out");
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
    }
}
