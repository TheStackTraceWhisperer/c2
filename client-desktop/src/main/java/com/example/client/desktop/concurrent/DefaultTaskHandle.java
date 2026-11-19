package com.example.client.desktop.concurrent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
public class DefaultTaskHandle<T> implements TaskHandle<T> {

    @Getter
    private final UUID id = UUID.randomUUID();

    @Getter
    private volatile TaskStatus status = TaskStatus.PENDING;

    @Getter
    private final CompletableFuture<T> future = new CompletableFuture<>();

    private final Object statusLock = new Object();
    private Future<?> rawFuture;
    private ScheduledFuture<?> timeoutFuture;
    private boolean mayInterruptPendingCancel = true;
    private volatile T result;
    private volatile Throwable error;

    public void setRawFuture(Future<?> rawFuture) {
        boolean cancelNow = false;
        boolean interrupt = true;
        synchronized (statusLock) {
            this.rawFuture = rawFuture;
            if (this.status == TaskStatus.CANCELLED || this.status == TaskStatus.TIMED_OUT) {
                cancelNow = true;
                interrupt = this.mayInterruptPendingCancel;
            }
        }
        if (cancelNow && rawFuture != null) {
            rawFuture.cancel(interrupt);
        }
    }

    public void setTimeoutFuture(ScheduledFuture<?> timeoutFuture) {
        boolean cancelNow = false;
        synchronized (statusLock) {
            this.timeoutFuture = timeoutFuture;
            if (this.status != TaskStatus.PENDING && this.status != TaskStatus.RUNNING) {
                cancelNow = true;
            }
        }
        if (cancelNow && timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
    }

    public void setRunning() {
        synchronized (statusLock) {
            if (this.status == TaskStatus.PENDING) {
                this.status = TaskStatus.RUNNING;
            }
        }
    }

    public boolean setSuccess(T result) {
        ScheduledFuture<?> timeoutToCancel;
        synchronized (statusLock) {
            if (this.status == TaskStatus.PENDING || this.status == TaskStatus.RUNNING) {
                this.result = result;
                this.status = TaskStatus.SUCCEEDED;
                this.future.complete(result);
                timeoutToCancel = this.timeoutFuture;
            } else {
                return false;
            }
        }
        if (timeoutToCancel != null) {
            timeoutToCancel.cancel(false);
        }
        return true;
    }

    public boolean setFailure(Throwable error) {
        ScheduledFuture<?> timeoutToCancel;
        synchronized (statusLock) {
            if (this.status == TaskStatus.PENDING || this.status == TaskStatus.RUNNING) {
                this.error = error;
                this.status = TaskStatus.FAILED;
                this.future.completeExceptionally(error);
                timeoutToCancel = this.timeoutFuture;
            } else {
                return false;
            }
        }
        if (timeoutToCancel != null) {
            timeoutToCancel.cancel(false);
        }
        return true;
    }

    public boolean setTimedOut() {
        Future<?> futureToCancel;
        TimeoutException timeoutEx = new TimeoutException("Task timed out");
        synchronized (statusLock) {
            if (this.status == TaskStatus.PENDING || this.status == TaskStatus.RUNNING) {
                this.error = timeoutEx;
                this.status = TaskStatus.TIMED_OUT;
                this.mayInterruptPendingCancel = true;
                this.future.completeExceptionally(timeoutEx);
                futureToCancel = this.rawFuture;
            } else {
                return false;
            }
        }
        if (futureToCancel != null) {
            futureToCancel.cancel(true);
        }
        return true;
    }

    @Override
    public Throwable getError() {
        return error;
    }

    @Override
    public T getResult() {
        return result;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        Future<?> futureToCancel;
        ScheduledFuture<?> timeoutToCancel;
        synchronized (statusLock) {
            if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) {
                status = TaskStatus.CANCELLED;
                mayInterruptPendingCancel = mayInterruptIfRunning;
                future.cancel(mayInterruptIfRunning);
                futureToCancel = this.rawFuture;
                timeoutToCancel = this.timeoutFuture;
            } else {
                return false;
            }
        }
        if (timeoutToCancel != null) {
            timeoutToCancel.cancel(false);
        }
        if (futureToCancel != null) {
            return futureToCancel.cancel(mayInterruptIfRunning);
        }
        return true;
    }
}
