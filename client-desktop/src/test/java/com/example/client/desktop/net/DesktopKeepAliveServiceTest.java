package com.example.client.desktop.net;

import com.example.common.dto.PongMessage;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DesktopKeepAliveServiceTest {

    private DesktopNetworkAdapter networkAdapter;
    private DesktopKeepAliveService keepAlive;

    @BeforeEach
    void setUp() {
        networkAdapter = mock(DesktopNetworkAdapter.class);
        keepAlive = new DesktopKeepAliveService(networkAdapter);
    }

    @AfterEach
    void tearDown() {
        keepAlive.shutdown();
    }

    @Test
    @DisplayName("start schedules periodic pings and stop cancels all pending pings")
    void testStartAndStopCancelsPendingPings() {
        AtomicInteger pingCount = new AtomicInteger(0);
        doAnswer(inv -> {
            pingCount.incrementAndGet();
            return null;
        }).when(networkAdapter).sendPing(anyLong(), anyString());

        keepAlive.start("char-1", 250L);
        assertTrue(keepAlive.isActive());

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> pingCount.get() >= 1);

        keepAlive.stop();
        assertFalse(keepAlive.isActive());
        assertEquals("idle", keepAlive.getStatus());

        int countAfterStop = pingCount.get();
        Awaitility.await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(50))
                .during(Duration.ofMillis(350))
                .atMost(Duration.ofMillis(1200))
                .until(() -> pingCount.get() == countAfterStop);
    }

    @Test
    @DisplayName("Duplicate start while already active is ignored")
    void testDuplicateStartGuarded() {
        keepAlive.start("char-1", 5000L);
        assertTrue(keepAlive.isActive());

        keepAlive.start("char-2", 5000L);

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(networkAdapter, times(1)).sendPing(anyLong(), eq("ping-char-1")));
        verify(networkAdapter, never()).sendPing(anyLong(), eq("ping-char-2"));
    }

    @Test
    @DisplayName("sendPing failure marks status failed without crashing loop")
    void testSendPingFailureSetsFailedStatus() {
        doThrow(new RuntimeException("socket closed"))
                .when(networkAdapter).sendPing(anyLong(), anyString());

        keepAlive.start("char-1", 5000L);

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertEquals("failed", keepAlive.getStatus()));
        assertTrue(keepAlive.isActive());
    }

    @Test
    @DisplayName("handlePong updates status, lastRttMs, and lastOkAt")
    void testHandlePongUpdatesTelemetry() {
        long now = System.currentTimeMillis();
        keepAlive.handlePong(new PongMessage(now - 25L, "ping-1"));

        assertEquals("ok", keepAlive.getStatus());
        assertTrue(keepAlive.getLastRttMs() >= 0L);
        assertNotNull(keepAlive.getLastOkAt());
    }
}
