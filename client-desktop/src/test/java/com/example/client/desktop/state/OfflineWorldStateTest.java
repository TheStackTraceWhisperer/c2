package com.example.client.desktop.state;

import com.example.client.desktop.camera.PolarCamera;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.window.WindowService;
import io.micronaut.context.BeanProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineWorldStateTest {

    @Test
    @DisplayName("OfflineWorldState can locate and read South San d'Oria zone DAT from disk")
    void testLoadZoneBytes() {
        WindowService windowService = Mockito.mock(WindowService.class);
        PolarCamera camera = new PolarCamera();
        ApplicationStateService stateService = Mockito.mock(ApplicationStateService.class);
        @SuppressWarnings("unchecked")
        BeanProvider<UnauthenticatedState> unauthProvider = Mockito.mock(BeanProvider.class);

        OfflineWorldState state = new OfflineWorldState(windowService, camera, stateService, unauthProvider);
        byte[] bytes = state.loadZoneBytes(230);

        assertNotNull(bytes, "Zone 230 DAT bytes should be resolved from filesystem or classpath");
        assertTrue(bytes.length > 1000, "Zone 230 DAT bytes should contain valid file data");
    }

    @Test
    @DisplayName("OfflineWorldState safely updates without throwing when window handle is 0 (headless)")
    void testHeadlessUpdateDoesNotThrow() {
        WindowService windowService = Mockito.mock(WindowService.class);
        Mockito.when(windowService.getHandle()).thenReturn(0L);

        PolarCamera camera = new PolarCamera();
        ApplicationStateService stateService = Mockito.mock(ApplicationStateService.class);
        @SuppressWarnings("unchecked")
        BeanProvider<UnauthenticatedState> unauthProvider = Mockito.mock(BeanProvider.class);

        OfflineWorldState state = new OfflineWorldState(windowService, camera, stateService, unauthProvider);

        assertDoesNotThrow(() -> state.onUpdate(0.016f));
        assertDoesNotThrow(state::onExit);
    }
}
