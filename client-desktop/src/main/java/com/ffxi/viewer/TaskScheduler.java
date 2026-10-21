package com.ffxi.viewer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * TaskScheduler manages running heavy operations (e.g. file loading, parsing)
 * on background Virtual Threads, and queuing their callbacks to execute on the
 * main/render thread (where the OpenGL context resides).
 */
public class TaskScheduler {
    private final ExecutorService backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Queue<Runnable> foregroundQueue = new ConcurrentLinkedQueue<>();

    /**
     * Submits a supplier to run on a background virtual thread.
     * When completed, the result is queued to run on the main thread via foregroundCallback.
     */
    public <T> void submit(Supplier<T> backgroundTask, Consumer<T> foregroundCallback, Consumer<Throwable> onError) {
        backgroundExecutor.submit(() -> {
            try {
                T result = backgroundTask.get();
                foregroundQueue.add(() -> foregroundCallback.accept(result));
            } catch (Throwable t) {
                if (onError != null) {
                    foregroundQueue.add(() -> onError.accept(t));
                } else {
                    t.printStackTrace();
                }
            }
        });
    }

    /**
     * Processes all queued callbacks on the main/foreground thread.
     * Should be called regularly (e.g. once per frame) from the main thread.
     */
    public void processForegroundTasks() {
        Runnable runnable;
        while ((runnable = foregroundQueue.poll()) != null) {
            try {
                runnable.run();
            } catch (Exception e) {
                System.err.println("[TaskScheduler] Error executing foreground task: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void shutdown() {
        backgroundExecutor.shutdown();
    }
}
