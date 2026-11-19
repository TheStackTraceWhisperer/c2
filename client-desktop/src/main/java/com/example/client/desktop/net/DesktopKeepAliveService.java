package com.example.client.desktop.net;

import com.example.common.dto.PongMessage;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class DesktopKeepAliveService {

    private final DesktopNetworkAdapter networkAdapter;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "desktop-keepalive");
        t.setDaemon(true);
        return t;
    });

    private volatile ScheduledFuture<?> task;

    @Getter private volatile String status = "idle";
    @Getter private volatile long lastRttMs = -1;
    @Getter private volatile Instant lastOkAt = null;

    private volatile String sessionId;
    private volatile long lastPingSentTimeMs;

    public synchronized void start(String sessionId, long intervalMs) {
        if (isActive()) {
            log.warn("DesktopKeepAliveService already active for session={}, ignoring duplicate start", this.sessionId);
            return;
        }
        this.sessionId = sessionId;
        this.status = "connected";
        long interval = Math.max(250L, intervalMs);

        this.task = scheduler.scheduleWithFixedDelay(this::runPingTick, 0L, interval, TimeUnit.MILLISECONDS);
        log.info("DesktopKeepAliveService started session={} interval={}ms", sessionId, interval);
    }

    private synchronized void runPingTick() {
        if (!isActive()) {
            return;
        }
        try {
            sendPing();
        } catch (Throwable t) {
            status = "failed";
            log.warn("Error running keepalive ping: {}", t.getMessage(), t);
        }
    }

    public synchronized void stop() {
        ScheduledFuture<?> current = this.task;
        this.task = null;
        if (current != null) {
            current.cancel(true);
        }
        status = "idle";
        log.info("DesktopKeepAliveService stopped");
    }

    @PreDestroy
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    public void sendPing() {
        lastPingSentTimeMs = System.currentTimeMillis();
        try {
            networkAdapter.sendPing(lastPingSentTimeMs, "ping-" + sessionId);
        } catch (Exception e) {
            status = "failed";
            log.warn("PING_ERR: {}", e.getMessage());
        }
    }

    public void handlePong(PongMessage resp) {
        long now = System.currentTimeMillis();
        long rtt = lastPingSentTimeMs > 0
                ? Math.max(0, now - lastPingSentTimeMs)
                : Math.max(0, now - resp.timestamp());
        status = "ok";
        lastRttMs = rtt;
        lastOkAt = Instant.now();
        log.info("PONG session={} rtt={}ms", sessionId, rtt);
    }

    public boolean isActive() {
        ScheduledFuture<?> current = this.task;
        return current != null && !current.isCancelled() && !current.isDone();
    }
}
