package com.example.client.desktop.net;

import com.example.client.desktop.concurrent.TaskHandle;
import com.example.client.desktop.concurrent.TaskScheduler;
import com.example.client.desktop.concurrent.TaskStatus;
import com.example.common.dto.PongMessage;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class DesktopKeepAliveService {

    private final DesktopNetworkAdapter networkAdapter;
    private final TaskScheduler taskScheduler;

    private TaskHandle<?> task;

    @Getter private volatile String status = "idle";
    @Getter private volatile long lastRttMs = -1;
    @Getter private volatile Instant lastOkAt = null;

    private String sessionId;
    private volatile long lastPingSentTimeMs;

    public void start(String sessionId, long intervalMs) {
        this.sessionId = sessionId;
        this.status = "connected";
        long interval = Math.max(250L, intervalMs);

        schedulePingLoop(interval);
        log.info("DesktopKeepAliveService started session={} interval={}ms", sessionId, interval);
    }

    private void schedulePingLoop(long intervalMs) {
        task = taskScheduler.submit(() -> {
            sendPing();
            return null;
        }, (res) -> {
            if (isActive()) {
                taskScheduler.submit(() -> {
                    Thread.sleep(intervalMs);
                    return null;
                }, (r) -> {
                    if (isActive()) {
                        schedulePingLoop(intervalMs);
                    }
                }, (err) -> log.warn("Error in keepalive delay: {}", err.getMessage()));
            }
        }, (err) -> {
            log.warn("Error running ping: {}", err.getMessage());
            if (isActive()) {
                taskScheduler.submit(() -> {
                    Thread.sleep(intervalMs);
                    return null;
                }, (r) -> {
                    if (isActive()) {
                        schedulePingLoop(intervalMs);
                    }
                }, (e) -> {});
            }
        });
    }

    public void stop() {
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        status = "idle";
        log.info("DesktopKeepAliveService stopped");
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
        long rtt = Math.max(0, System.currentTimeMillis() - resp.timestamp());
        status = "ok";
        lastRttMs = rtt;
        lastOkAt = Instant.now();
        log.debug("PONG session={} rtt={}ms", sessionId, rtt);
    }

    public boolean isActive() {
        return task != null && task.getStatus() != TaskStatus.CANCELLED;
    }
}
