package com.example.client.desktop.engine;

import com.example.client.desktop.concurrent.TaskScheduler;
import com.example.client.desktop.engine.services.imgui.ImGuiService;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.time.FrameTimeService;
import com.example.client.desktop.engine.services.window.WindowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class EngineTest {

    @Test
    @DisplayName("tick drains foreground tasks, updates services once, and does not call redundant windowService.pollEvents()")
    void testTickDoesNotCallRedundantPollEvents() {
        ApplicationLoopPolicy policy = ApplicationLoopPolicy.frames(1);
        ApplicationStateService stateService = mock(ApplicationStateService.class);
        WindowService windowService = mock(WindowService.class);
        FrameTimeService frameTime = mock(FrameTimeService.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        IService extraService = mock(IService.class);

        when(windowService.executionOrder()).thenReturn(Integer.MIN_VALUE + 1);
        when(frameTime.executionOrder()).thenReturn(Integer.MIN_VALUE + 3);
        when(frameTime.getDeltaTimeSeconds()).thenReturn(0.016f);
        when(extraService.executionOrder()).thenReturn(0);

        Engine engine = new Engine(
                policy,
                stateService,
                List.of(extraService, windowService, frameTime),
                windowService,
                frameTime,
                taskScheduler
        );

        engine.init();
        engine.tick();
        engine.shutdown();

        verify(windowService, never()).pollEvents();
        verify(windowService, times(1)).update();
        verify(taskScheduler, times(1)).processForegroundTasks();
        verify(windowService, times(1)).swapBuffers();
    }

    @Test
    @DisplayName("shutdown only stops started services when init fails partway")
    void testShutdownOnlyStopsStartedServices() {
        ApplicationLoopPolicy policy = ApplicationLoopPolicy.skip();
        ApplicationStateService stateService = mock(ApplicationStateService.class);
        WindowService windowService = mock(WindowService.class);
        FrameTimeService frameTime = mock(FrameTimeService.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);

        IService startedService = mock(IService.class);
        IService failingStartService = mock(IService.class);
        IService unstartedService = mock(IService.class);

        when(startedService.executionOrder()).thenReturn(1);
        when(failingStartService.executionOrder()).thenReturn(2);
        when(unstartedService.executionOrder()).thenReturn(3);

        doThrow(new IllegalStateException("start failed")).when(failingStartService).start();

        Engine engine = new Engine(
                policy,
                stateService,
                List.of(unstartedService, failingStartService, startedService),
                windowService,
                frameTime,
                taskScheduler
        );

        assertThrows(IllegalStateException.class, engine::run);

        verify(startedService, times(1)).start();
        verify(failingStartService, times(1)).start();
        verify(unstartedService, never()).start();

        verify(startedService, times(1)).stop();
        verify(failingStartService, never()).stop();
        verify(unstartedService, never()).stop();
    }

    @Test
    @DisplayName("shutdown catches stop exceptions and continues stopping remaining services in reverse order")
    void testShutdownCatchesExceptionsAndContinuesInReverseOrder() {
        ApplicationLoopPolicy policy = ApplicationLoopPolicy.skip();
        ApplicationStateService stateService = mock(ApplicationStateService.class);
        WindowService windowService = mock(WindowService.class);
        FrameTimeService frameTime = mock(FrameTimeService.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);

        IService first = mock(IService.class);
        IService second = mock(IService.class);
        IService third = mock(IService.class);

        when(first.executionOrder()).thenReturn(1);
        when(second.executionOrder()).thenReturn(2);
        when(third.executionOrder()).thenReturn(3);
        doThrow(new RuntimeException("stop failure")).when(second).stop();

        Engine engine = new Engine(
                policy,
                stateService,
                List.of(third, first, second),
                windowService,
                frameTime,
                taskScheduler
        );

        engine.init();
        assertDoesNotThrow(engine::shutdown);

        InOrder inOrder = inOrder(third, second, first);
        inOrder.verify(third).stop();
        inOrder.verify(second).stop();
        inOrder.verify(first).stop();
    }

    @Test
    @DisplayName("ImGuiService.stop before initialization is a safe no-op")
    void testImGuiServiceStopWhenUninitialized() {
        WindowService windowService = mock(WindowService.class);
        ImGuiService imGuiService = new ImGuiService(windowService);
        assertDoesNotThrow(imGuiService::stop);
    }
}
