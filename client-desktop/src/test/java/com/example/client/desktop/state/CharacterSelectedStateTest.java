package com.example.client.desktop.state;

import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.ui.CharacterPanel;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.lobby.dto.CharacterSummary;
import io.micronaut.context.BeanProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterSelectedStateTest {

    private CharacterPanel panel;
    private DebugLogPanel debugLog;
    private ClientState clientState;
    private DesktopNetworkAdapter networkAdapter;
    private BeanProvider<UnauthenticatedState> unauthProvider;
    private BeanProvider<AuthenticatedState> authProvider;
    private BeanProvider<InGameState> inGameProvider;
    private UnauthenticatedState unauthState;
    private AuthenticatedState authState;
    private InGameState inGameState;
    private ApplicationStateService stateService;
    private CharacterSelectedState state;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        panel = Mockito.mock(CharacterPanel.class);
        debugLog = Mockito.mock(DebugLogPanel.class);
        clientState = new ClientState();
        networkAdapter = Mockito.mock(DesktopNetworkAdapter.class);
        unauthProvider = Mockito.mock(BeanProvider.class);
        authProvider = Mockito.mock(BeanProvider.class);
        inGameProvider = Mockito.mock(BeanProvider.class);
        unauthState = Mockito.mock(UnauthenticatedState.class);
        authState = Mockito.mock(AuthenticatedState.class);
        inGameState = Mockito.mock(InGameState.class);

        when(unauthProvider.get()).thenReturn(unauthState);
        when(authProvider.get()).thenReturn(authState);
        when(inGameProvider.get()).thenReturn(inGameState);

        jakarta.inject.Provider<ApplicationState> initialProvider = () -> unauthState;
        stateService = new ApplicationStateService(initialProvider);

        clientState.setAccountId(100L);
        clientState.setAuthToken("token-123");
        clientState.setSelectedCharacterId(200L);
        clientState.setConnectionState(ClientConnectionState.IN_WORLD);
        clientState.updateCharacters(List.of(
                new CharacterSummary(200L, "Aldo", 1, (short) 1, (short) 0, 230, 10, (short) 0, (short) 1, (short) 0)
        ));

        state = new CharacterSelectedState(
                panel, debugLog, stateService, clientState, networkAdapter,
                unauthProvider, authProvider, inGameProvider
        );
        state.init(100L, 200L, "Aldo", 230);
        stateService.pushState(() -> state);
    }

    @Test
    @DisplayName("Sign Out transitions cleanly to UnauthenticatedState without overwriting with AuthenticatedState")
    void testSignOutTransitionsCleanlyToUnauthenticatedState() {
        when(panel.isSignOutRequested()).thenReturn(true);
        doAnswer(inv -> {
            clientState.reset();
            return null;
        }).when(networkAdapter).sendLogout();

        state.onUpdate(0.016f);

        assertSame(unauthState, stateService.peek(),
                "Active state must remain UnauthenticatedState after Sign Out");
        verify(networkAdapter, times(1)).sendLogout();
        verify(unauthProvider, times(1)).get();
        verify(authProvider, never()).get();
        verify(panel, times(1)).clearIntents();
        assertEquals(ClientConnectionState.DISCONNECTED, clientState.getConnectionState());
    }

    @Test
    @DisplayName("Deselect transitions cleanly to AuthenticatedState")
    void testDeselectTransitionsToAuthenticatedState() {
        when(panel.isDeselectRequested()).thenReturn(true);

        state.onUpdate(0.016f);

        assertSame(authState, stateService.peek());
        verify(authState).init(100L);
        assertNull(clientState.getSelectedCharacterId());
        assertEquals(ClientConnectionState.IN_LOBBY, clientState.getConnectionState());
    }

    @Test
    @DisplayName("IN_ZONE connection state transitions to InGameState")
    void testInZoneTransitionsToInGameState() {
        clientState.setCurrentZoneId(230);
        clientState.setConnectionState(ClientConnectionState.IN_ZONE);

        state.onUpdate(0.016f);

        assertSame(inGameState, stateService.peek());
        verify(inGameState).init(100L, 200L, "Aldo", 230, 5000L);
    }
}
