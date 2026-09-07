package com.example.client.desktop;

import com.example.client.desktop.concurrent.TaskHandle;
import com.example.client.desktop.concurrent.TaskSchedulerService;
import com.example.client.desktop.concurrent.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskSchedulerServiceTest {

    private TaskSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TaskSchedulerService();
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    void testForegroundTaskExecution() {
        AtomicBoolean ran = new AtomicBoolean(false);
        scheduler.runOnMainThread(() -> ran.set(true));

        assertFalse(ran.get(), "Should not run before processForegroundTasks");
        scheduler.processForegroundTasks();
        assertTrue(ran.get(), "Should have run after processForegroundTasks");
    }

    @Test
    void testSuccessfulBackgroundTask() throws InterruptedException {
        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();

        TaskHandle<String> handle = scheduler.submit(
                () -> "hello-world",
                result -> {
                    resultRef.set(result);
                    callbackLatch.countDown();
                },
                throwable -> fail("Should not fail: " + throwable.getMessage())
        );

        assertNotNull(handle);
        // Wait a bit for the virtual thread to complete and post to foreground
        Thread.sleep(100);

        scheduler.processForegroundTasks();
        assertTrue(callbackLatch.await(1, TimeUnit.SECONDS));
        assertEquals("hello-world", resultRef.get());
        assertEquals(TaskStatus.SUCCEEDED, handle.getStatus());
        assertEquals("hello-world", handle.getResult());
    }

    @Test
    void testFailingBackgroundTask() throws InterruptedException {
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        TaskHandle<String> handle = scheduler.submit(
                () -> {
                    throw new IllegalStateException("boom");
                },
                result -> fail("Should not succeed"),
                throwable -> {
                    errorRef.set(throwable);
                    errorLatch.countDown();
                }
        );

        Thread.sleep(100);
        scheduler.processForegroundTasks();

        assertTrue(errorLatch.await(1, TimeUnit.SECONDS));
        assertEquals(TaskStatus.FAILED, handle.getStatus());
        assertNotNull(errorRef.get());
        assertEquals("boom", errorRef.get().getMessage());
    }

    @Test
    void testTaskTimeout() throws InterruptedException {
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        TaskHandle<String> handle = scheduler.submit(
                () -> {
                    Thread.sleep(1000);
                    return "late";
                },
                50, // 50ms timeout
                result -> fail("Should not succeed"),
                throwable -> {
                    errorRef.set(throwable);
                    errorLatch.countDown();
                }
        );

        // Wait for timeout watchdog to trigger
        Thread.sleep(150);
        scheduler.processForegroundTasks();

        assertTrue(errorLatch.await(1, TimeUnit.SECONDS));
        assertEquals(TaskStatus.TIMED_OUT, handle.getStatus());
        assertNotNull(errorRef.get());
    }
}
