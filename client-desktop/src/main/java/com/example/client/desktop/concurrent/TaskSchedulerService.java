package com.example.client.desktop.concurrent;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class TaskSchedulerService implements TaskScheduler, AutoCloseable {

    public static final int MAX_FOREGROUND_TASKS_PER_FRAME = 256;

    private final ExecutorService backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService schedulerWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-scheduler-watchdog");
        t.setDaemon(true);
        return t;
    });
    private final Queue<Runnable> foregroundQueue = new ConcurrentLinkedQueue<>();

    @PreDestroy
    public void stop() {
        log.info("Shutting down TaskSchedulerService...");
        backgroundExecutor.shutdown();
        schedulerWatchdog.shutdown();
        try {
            if (!backgroundExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow();
            }
            if (!schedulerWatchdog.awaitTermination(1, TimeUnit.SECONDS)) {
                schedulerWatchdog.shutdownNow();
            }
        } catch (InterruptedException e) {
            backgroundExecutor.shutdownNow();
            schedulerWatchdog.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("TaskSchedulerService shut down successfully.");
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public <T> TaskHandle<T> submit(Callable<T> task) {
        return submit(task, null, null);
    }

    @Override
    public <T> TaskHandle<T> submit(Callable<T> task, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        return submit(task, 0, onSuccess, onError);
    }

    @Override
    public <T> TaskHandle<T> submit(Callable<T> task, long timeoutMs, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        DefaultTaskHandle<T> handle = new DefaultTaskHandle<>();
        
        Future<?> rawFuture = backgroundExecutor.submit(() -> {
            handle.setRunning();
            log.debug("Starting background task id={}", handle.getId());
            try {
                T result = task.call();
                if (handle.setSuccess(result)) {
                    log.debug("Background task id={} succeeded", handle.getId());
                    if (onSuccess != null) {
                        runOnMainThread(() -> onSuccess.accept(result));
                    }
                }
            } catch (Throwable t) {
                if (handle.setFailure(t)) {
                    log.error("Background task id={} failed: {}", handle.getId(), t.getMessage(), t);
                    if (onError != null) {
                        runOnMainThread(() -> onError.accept(t));
                    }
                }
            }
        });
        handle.setRawFuture(rawFuture);

        if (timeoutMs > 0) {
            ScheduledFuture<?> timeoutFuture = schedulerWatchdog.schedule(() -> {
                if (handle.setTimedOut()) {
                    log.warn("Task id={} timed out after {}ms", handle.getId(), timeoutMs);
                    if (onError != null) {
                        runOnMainThread(() -> onError.accept(handle.getError()));
                    }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            handle.setTimeoutFuture(timeoutFuture);
        }

        return handle;
    }

    @Override
    public void runOnMainThread(Runnable action) {
        if (action == null) return;
        foregroundQueue.add(action);
    }

    @Override
    public void processForegroundTasks() {
        int processed = 0;
        Runnable task;
        while (processed < MAX_FOREGROUND_TASKS_PER_FRAME && (task = foregroundQueue.poll()) != null) {
            processed++;
            try {
                task.run();
            } catch (Throwable t) {
                log.error("[TaskScheduler] Error executing foreground task: {}", t.getMessage(), t);
            }
        }
    }
}
