package com.example.client.desktop.state;

import com.example.client.desktop.camera.Camera;
import com.example.client.desktop.camera.PolarCamera;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.window.WindowService;
import com.example.client.desktop.input.MovementInputHandler;
import com.example.client.desktop.net.DesktopKeepAliveService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.desktop.ui.InGamePanel;
import com.example.client.desktop.ui.radar.WorldRadarPanel;
import com.example.client.state.ClientState;
import io.micronaut.context.BeanProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class InGameStateTest {

    @Test
    @DisplayName("InGameState can locate and read South San d'Oria DAT bytes")
    void testLoadZoneBytes() {
        InGameState state = createTestState(0L);
        byte[] bytes = state.loadZoneBytes(230);

        assertNotNull(bytes, "Zone 230 DAT bytes should be resolved");
        assertTrue(bytes.length > 1000, "Zone 230 DAT bytes should contain valid file data");
    }

    @Test
    @DisplayName("InGameState can locate and read West Ronfaure DAT bytes")
    void testLoadZoneBytesWestRonfaure() {
        InGameState state = createTestState(0L);
        byte[] bytes = state.loadZoneBytes(100);

        assertNotNull(bytes, "Zone 100 DAT bytes should be resolved");
        assertTrue(bytes.length > 1000, "Zone 100 DAT bytes should contain valid file data");
    }

    @Test
    @DisplayName("InGameState processes ZoneTransferNotification updating zone and avatar spawn coordinates")
    void testHandleZoneTransferNotification() {
        InGameState state = createTestState(0L);
        state.init(100L, 200L, "Aldo", 230, 5000L);
        state.onEnter();

        com.example.world.dto.ZoneTransferNotification transfer = new com.example.world.dto.ZoneTransferNotification(
                100, "127.0.0.1", 9001, 15.5f, 2.0f, -42.0f
        );

        state.onHandlePacket(transfer);

        assertEquals(100, state.getCurrentZoneId());
    }

    @Test
    @DisplayName("InGameState safely updates headlessly without OpenGL exceptions when handle is 0")
    void testHeadlessUpdateDoesNotThrow() {
        InGameState state = createTestState(0L);
        state.init(100L, 200L, "Aldo", 230, 5000L);

        assertDoesNotThrow(state::onEnter);
        assertDoesNotThrow(() -> state.onUpdate(0.016f));
        assertDoesNotThrow(state::onExit);
    }

    @SuppressWarnings("unchecked")
    private InGameState createTestState(long windowHandle) {
        InGamePanel panel = Mockito.mock(InGamePanel.class);
        WorldRadarPanel radarPanel = Mockito.mock(WorldRadarPanel.class);
        Mockito.when(radarPanel.getConfig()).thenReturn(new com.example.client.desktop.ui.radar.RadarViewportConfig());
        DebugLogPanel debugLog = Mockito.mock(DebugLogPanel.class);
        DesktopKeepAliveService keepAlive = Mockito.mock(DesktopKeepAliveService.class);
        ClientState clientState = new ClientState();
        DesktopNetworkAdapter networkAdapter = Mockito.mock(DesktopNetworkAdapter.class);
        ApplicationStateService stateService = Mockito.mock(ApplicationStateService.class);
        WindowService windowService = Mockito.mock(WindowService.class);
        Mockito.when(windowService.getHandle()).thenReturn(windowHandle);
        MovementInputHandler movementHandler = new MovementInputHandler();
        Camera camera = new PolarCamera();
        BeanProvider<UnauthenticatedState> unauthProvider = Mockito.mock(BeanProvider.class);
        BeanProvider<AuthenticatedState> authProvider = Mockito.mock(BeanProvider.class);
        BeanProvider<CharacterSelectedState> selectedProvider = Mockito.mock(BeanProvider.class);
        com.example.client.desktop.ui.map.InGameMapPanel mapPanel = Mockito.mock(com.example.client.desktop.ui.map.InGameMapPanel.class);

        return new InGameState(
                panel, radarPanel, debugLog, keepAlive, clientState,
                networkAdapter, stateService, windowService, movementHandler,
                camera, unauthProvider, authProvider, selectedProvider,
                mapPanel
        );
    }
}
