package com.example.client.desktop;

import com.example.client.desktop.concurrent.DefaultTaskHandle;
import com.example.client.desktop.concurrent.TaskHandle;
import com.example.client.desktop.concurrent.TaskSchedulerService;
import com.example.client.desktop.concurrent.TaskStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskSchedulerServiceTest {

    private TaskSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TaskSchedulerService();
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
    void testForegroundTaskDrainBoundedPerFrame() {
        AtomicInteger count = new AtomicInteger(0);
        int total = TaskSchedulerService.MAX_FOREGROUND_TASKS_PER_FRAME + 44;
        for (int i = 0; i < total; i++) {
            scheduler.runOnMainThread(count::incrementAndGet);
        }

        scheduler.processForegroundTasks();
        assertEquals(TaskSchedulerService.MAX_FOREGROUND_TASKS_PER_FRAME, count.get());

        scheduler.processForegroundTasks();
        assertEquals(total, count.get());
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
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> {
            scheduler.processForegroundTasks();
            return callbackLatch.getCount() == 0;
        });

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

        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> {
            scheduler.processForegroundTasks();
            return errorLatch.getCount() == 0;
        });

        assertTrue(errorLatch.await(1, TimeUnit.SECONDS));
        assertEquals(TaskStatus.FAILED, handle.getStatus());
        assertNotNull(errorRef.get());
        assertEquals("boom", errorRef.get().getMessage());
        assertEquals("boom", handle.getError().getMessage());
    }

    @Test
    void testTaskTimeout() throws InterruptedException {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        TaskHandle<String> handle = scheduler.submit(
                () -> {
                    startedLatch.countDown();
                    new CountDownLatch(1).await();
                    return "late";
                },
                50, // 50ms timeout
                result -> fail("Should not succeed"),
                throwable -> {
                    errorRef.set(throwable);
                    errorLatch.countDown();
                }
        );

        assertTrue(startedLatch.await(1, TimeUnit.SECONDS));
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> {
            scheduler.processForegroundTasks();
            return errorLatch.getCount() == 0;
        });

        assertEquals(TaskStatus.TIMED_OUT, handle.getStatus());
        assertInstanceOf(TimeoutException.class, errorRef.get());
        assertInstanceOf(TimeoutException.class, handle.getError());
        assertTrue(handle.getFuture().isCompletedExceptionally());
    }

    @Test
    void testTaskCancellation() throws InterruptedException {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch interruptedLatch = new CountDownLatch(1);

        TaskHandle<String> handle = scheduler.submit(
                () -> {
                    startedLatch.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        interruptedLatch.countDown();
                        throw e;
                    }
                    return "done";
                },
                result -> fail("Cancelled task should not invoke onSuccess"),
                err -> fail("Cancelled task should not invoke onError")
        );

        assertTrue(startedLatch.await(1, TimeUnit.SECONDS));
        assertTrue(handle.cancel(true));
        assertEquals(TaskStatus.CANCELLED, handle.getStatus());
        assertTrue(handle.getFuture().isCancelled());
        assertTrue(interruptedLatch.await(1, TimeUnit.SECONDS));
        assertFalse(handle.cancel(true), "Cancelling twice should return false");

        scheduler.processForegroundTasks();
    }

    @Test
    void testLateRawFutureCancelledIfAlreadyCancelled() {
        DefaultTaskHandle<Void> handle = new DefaultTaskHandle<>();
        assertTrue(handle.cancel(true));
        Future<?> rawFuture = mock(Future.class);

        handle.setRawFuture(rawFuture);

        verify(rawFuture).cancel(true);
    }
}
