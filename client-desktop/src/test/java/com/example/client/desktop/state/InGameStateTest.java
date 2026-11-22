package com.example.client.desktop.state;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.camera.Camera;
import com.example.client.desktop.scene.SceneCamera;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InGameStateTest {

    @Test
    @DisplayName("InGameState can locate and read South San d'Oria DAT bytes")
    void testLoadZoneBytes() {
        assumeTrue(FfxiPathConfig.resolveRomFile("ROM/1/31.DAT").isPresent(),
                "Retail FFXI ROM/1/31.DAT not found under " + FfxiPathConfig.resolveFfxiInstallDirectory());
        InGameState state = createTestState(0L);
        byte[] bytes = state.loadZoneBytes(230);

        assertNotNull(bytes, "Zone 230 DAT bytes should be resolved");
        assertTrue(bytes.length > 1000, "Zone 230 DAT bytes should contain valid file data");
    }

    @Test
    @DisplayName("InGameState can locate and read West Ronfaure DAT bytes")
    void testLoadZoneBytesWestRonfaure() {
        assumeTrue(FfxiPathConfig.resolveRomFile("ROM/1/30.DAT").isPresent(),
                "Retail FFXI ROM/1/30.DAT not found under " + FfxiPathConfig.resolveFfxiInstallDirectory());
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

    @Test
    @DisplayName("InGameState wires panel.isMapToggleRequested() to inGameMapPanel.toggle() and handles PongMessage cleanly")
    void testMapToggleIntentAndSinglePongHandling() {
        InGamePanel panel = Mockito.mock(InGamePanel.class);
        Mockito.when(panel.isMapToggleRequested()).thenReturn(true);
        Mockito.when(panel.getWarpRequestedZoneId()).thenReturn(-1);
        WorldRadarPanel radarPanel = Mockito.mock(WorldRadarPanel.class);
        Mockito.when(radarPanel.getConfig()).thenReturn(new com.example.client.desktop.ui.radar.RadarViewportConfig());
        DebugLogPanel debugLog = Mockito.mock(DebugLogPanel.class);
        DesktopKeepAliveService keepAlive = Mockito.mock(DesktopKeepAliveService.class);
        ClientState clientState = new ClientState();
        DesktopNetworkAdapter networkAdapter = Mockito.mock(DesktopNetworkAdapter.class);
        ApplicationStateService stateService = Mockito.mock(ApplicationStateService.class);
        WindowService windowService = Mockito.mock(WindowService.class);
        Mockito.when(windowService.getHandle()).thenReturn(0L);
        MovementInputHandler movementHandler = new MovementInputHandler();
        Camera camera = new SceneCamera();
        @SuppressWarnings("unchecked")
        BeanProvider<UnauthenticatedState> unauthProvider = Mockito.mock(BeanProvider.class);
        @SuppressWarnings("unchecked")
        BeanProvider<AuthenticatedState> authProvider = Mockito.mock(BeanProvider.class);
        @SuppressWarnings("unchecked")
        BeanProvider<CharacterSelectedState> selectedProvider = Mockito.mock(BeanProvider.class);
        com.example.client.desktop.ui.map.InGameMapPanel mapPanel = Mockito.mock(com.example.client.desktop.ui.map.InGameMapPanel.class);

        InGameState state = new InGameState(
                panel, radarPanel, debugLog, keepAlive, clientState,
                networkAdapter, stateService, windowService, movementHandler,
                camera, unauthProvider, authProvider, selectedProvider,
                mapPanel
        );
        assertSame(camera, state.getWorldScene().getCamera(), "InGameState and WorldScene must share the same SceneCamera instance");
        state.init(100L, 200L, "Aldo", 230, 5000L);
        state.onEnter();
        state.onUpdate(0.016f);

        Mockito.verify(mapPanel, Mockito.times(1)).toggle();

        com.example.common.dto.PongMessage pong = new com.example.common.dto.PongMessage(1000L, "ping-200");
        state.onHandlePacket(pong);
        Mockito.verify(keepAlive, Mockito.times(1)).handlePong(pong);
    }

    @Test
    @DisplayName("DebugLogPanel capture intents are drained cleanly by InGameState in headless mode")
    void testDebugLogCaptureIntentsDrainedHeadlessly() {
        InGamePanel panel = Mockito.mock(InGamePanel.class);
        Mockito.when(panel.getWarpRequestedZoneId()).thenReturn(-1);
        WorldRadarPanel radarPanel = Mockito.mock(WorldRadarPanel.class);
        Mockito.when(radarPanel.getConfig()).thenReturn(new com.example.client.desktop.ui.radar.RadarViewportConfig());
        DebugLogPanel debugLog = Mockito.spy(new DebugLogPanel());
        Mockito.doNothing().when(debugLog).render();
        DesktopKeepAliveService keepAlive = Mockito.mock(DesktopKeepAliveService.class);
        ClientState clientState = new ClientState();
        DesktopNetworkAdapter networkAdapter = Mockito.mock(DesktopNetworkAdapter.class);
        ApplicationStateService stateService = Mockito.mock(ApplicationStateService.class);
        WindowService windowService = Mockito.mock(WindowService.class);
        Mockito.when(windowService.getHandle()).thenReturn(0L);
        MovementInputHandler movementHandler = new MovementInputHandler();
        Camera camera = new SceneCamera();
        @SuppressWarnings("unchecked")
        BeanProvider<UnauthenticatedState> unauthProvider = Mockito.mock(BeanProvider.class);
        @SuppressWarnings("unchecked")
        BeanProvider<AuthenticatedState> authProvider = Mockito.mock(BeanProvider.class);
        @SuppressWarnings("unchecked")
        BeanProvider<CharacterSelectedState> selectedProvider = Mockito.mock(BeanProvider.class);
        com.example.client.desktop.ui.map.InGameMapPanel mapPanel = Mockito.mock(com.example.client.desktop.ui.map.InGameMapPanel.class);

        InGameState state = new InGameState(
                panel, radarPanel, debugLog, keepAlive, clientState,
                networkAdapter, stateService, windowService, movementHandler,
                camera, unauthProvider, authProvider, selectedProvider,
                mapPanel
        );
        assertSame(camera, state.getWorldScene().getCamera(), "InGameState and WorldScene must share the same SceneCamera instance");
        state.init(100L, 200L, "Aldo", 230, 5000L);
        state.onEnter();

        setPanelCaptureIntent(debugLog, "screenshotRequested");
        setPanelCaptureIntent(debugLog, "renderDocCaptureRequested");
        assertTrue(debugLog.isScreenshotRequested());
        assertTrue(debugLog.isRenderDocCaptureRequested());

        assertDoesNotThrow(() -> state.onUpdate(0.016f));

        assertFalse(debugLog.isScreenshotRequested(), "DebugLogPanel screenshot intent must be cleared");
        assertFalse(debugLog.isRenderDocCaptureRequested(), "DebugLogPanel RenderDoc intent must be cleared");
        assertTrue(debugLog.getLinesSnapshot().stream().anyMatch(line -> line.contains("Screenshot skipped")),
                "Headless screenshot intent should record skip message");
        assertTrue(debugLog.getLinesSnapshot().stream().anyMatch(line -> line.contains("RenderDoc")),
                "Headless RenderDoc intent should record capture status");
        state.onExit();
    }

    private static void setPanelCaptureIntent(DebugLogPanel panel, String fieldName) {
        try {
            var field = DebugLogPanel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(panel, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
        Camera camera = new SceneCamera();
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
