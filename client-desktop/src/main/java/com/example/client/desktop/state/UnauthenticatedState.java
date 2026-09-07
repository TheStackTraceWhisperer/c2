package com.example.client.desktop.state;

import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.net.ClientState;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.properties.DesktopClientProperties;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.desktop.ui.LoginPanel;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.GL11;

@Slf4j
@Prototype
@Named("initial")
@RequiredArgsConstructor
public class UnauthenticatedState implements ApplicationState {

    private final LoginPanel panel;
    private final DebugLogPanel debugLog;
    private final DesktopNetworkAdapter gateway;
    private final ApplicationStateService stateService;
    private final ClientState clientState;
    private final BeanProvider<AuthenticatedState> authenticatedProvider;
    private final DesktopClientProperties props;

    private String host;
    private int port;
    private boolean connected = false;

    @Override
    public void onEnter() {
        host = props.getDefaultServerHost();
        port = props.getDefaultServerPort();
        debugLog.log("Ready to connect — " + host + ":" + port);
    }

    @Override
    public void onUpdate(float dt) {
        GL11.glClearColor(0.07f, 0.07f, 0.09f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        panel.render(clientState);
        debugLog.render();

        if (panel.isLoginRequested()) {
            doLogin();
        }
        panel.clearIntents();

        // React to inbound state updates from authentication
        if (clientState.getPhase() == ClientState.AppPhase.LOBBY_CHAR_SELECT) {
            Long accId = clientState.getAccountId();
            if (accId != null) {
                debugLog.log("LOGIN_OK account=" + accId);
                AuthenticatedState next = authenticatedProvider.get();
                next.init(accId);
                stateService.changeState(() -> next);
            }
        }
    }

    @Override
    public void onExit() {}

    private void doLogin() {
        debugLog.log("Connecting to " + host + ":" + port + "...");
        clientState.sendLogin(panel.getUsername(), panel.getPassword());
    }
}
