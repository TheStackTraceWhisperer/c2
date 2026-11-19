package com.example.client.desktop.engine;

import com.example.client.desktop.concurrent.TaskScheduler;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.time.FrameTimeService;
import com.example.client.desktop.engine.services.window.WindowService;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Singleton
public final class Engine implements Runnable {

    private enum EngineState { NEW, INITIALIZED, SHUTDOWN }

    private final ApplicationLoopPolicy loopPolicy;
    private final ApplicationStateService stateService;
    private final List<IService> sortedServices;
    private final WindowService windowService;
    private final FrameTimeService frameTime;
    private final TaskScheduler taskScheduler;

    private final List<IService> startedServices = new ArrayList<>();
    private EngineState state = EngineState.NEW;
    private int frames = 0;

    public Engine(
            ApplicationLoopPolicy loopPolicy,
            ApplicationStateService stateService,
            List<IService> services,
            WindowService windowService,
            FrameTimeService frameTime,
            TaskScheduler taskScheduler
    ) {
        this.loopPolicy = loopPolicy;
        this.stateService = stateService;
        this.windowService = windowService;
        this.frameTime = frameTime;
        this.taskScheduler = taskScheduler;

        // Defensive copy to prevent UnsupportedOperationException if services is unmodifiable
        this.sortedServices = new ArrayList<>(services);
        this.sortedServices.sort(Comparator.comparingInt(IService::executionOrder));
    }

    public void init() {
        if (state != EngineState.NEW) {
            log.warn("Engine already initialized");
            return;
        }
        log.info("Initializing Catalyst Engine");

        for (IService service : sortedServices) {
            log.debug("Starting service: {}", service.getClass().getSimpleName());
            service.start();
            startedServices.add(service);
        }
        state = EngineState.INITIALIZED;
        log.info("Engine initialized successfully");
    }

    public void tick() {
        if (state != EngineState.INITIALIZED) {
            throw new IllegalStateException("Cannot tick uninitialized engine");
        }

        // Drains network callbacks and queued tasks safely on the render thread
        taskScheduler.processForegroundTasks();

        for (IService service : sortedServices) service.update();
        float dt = frameTime.getDeltaTimeSeconds();
        for (IService service : sortedServices) service.update(dt);
        for (IService service : sortedServices) service.postUpdate();

        windowService.swapBuffers();
        frames++;
    }

    private void mainLoop() {
        while (loopPolicy.continueRunning(frames, windowService.getHandle())
                && !stateService.isEmpty()) {
            tick();
        }
    }

    public void shutdown() {
        if (state == EngineState.SHUTDOWN) return;
        log.info("Shutting down Catalyst Engine");

        for (int i = startedServices.size() - 1; i >= 0; i--) {
            IService service = startedServices.get(i);
            log.debug("Stopping service: {}", service.getClass().getSimpleName());
            try {
                service.stop();
            } catch (Throwable t) {
                log.error("Error stopping service {}: {}", service.getClass().getSimpleName(), t.getMessage(), t);
            }
        }
        startedServices.clear();
        state = EngineState.SHUTDOWN;
    }

    @Override
    public void run() {
        try {
            init();
            mainLoop();
        } finally {
            shutdown();
        }
    }
}
