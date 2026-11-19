package com.example.client.desktop.net;

import com.example.client.DefaultClientConfiguration;
import com.example.client.QuicClient;
import com.example.client.desktop.concurrent.TaskSchedulerService;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.PongMessage;
import com.example.common.handler.PacketHandler;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.lobby.dto.CharacterSummary;
import com.example.login.dto.LoginResponse;
import com.example.login.dto.ReconnectResponse;
import com.example.zone.dto.PlayerMovementRequest;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DesktopNetworkAdapterTest {

    private QuicClient quicClient;
    private TaskSchedulerService taskScheduler;
    private ClientState clientState;
    private DefaultClientConfiguration clientConfiguration;
    private ApplicationStateService stateService;
    private DesktopNetworkAdapter adapter;

    @BeforeEach
    void setUp() {
        quicClient = mock(QuicClient.class);
        taskScheduler = new TaskSchedulerService();
        clientState = new ClientState();
        clientConfiguration = new DefaultClientConfiguration();
        stateService = mock(ApplicationStateService.class);

        adapter = new DesktopNetworkAdapter(
                quicClient,
                taskScheduler,
                clientState,
                clientConfiguration,
                stateService
        );
    }

    @AfterEach
    void tearDown() {
        adapter.shutdown();
        taskScheduler.stop();
    }

    @Test
    @DisplayName("Outbound movement packets are sent in strict FIFO order on single-threaded executor")
    void testOutboundPacketStrictFifoOrdering() throws Exception {
        when(quicClient.isConnected()).thenReturn(true);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Long> sentTicks = Collections.synchronizedList(new ArrayList<>());

        when(quicClient.send(any(PacketMessage.class))).thenAnswer(inv -> {
            PacketMessage msg = inv.getArgument(0);
            if (msg instanceof PlayerMovementRequest req) {
                if (req.clientTick() == 1L) {
                    firstStarted.countDown();
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                }
                sentTicks.add(req.clientTick());
            }
            return null;
        });

        adapter.sendMovement(0, 0, 0, 1, 0, 0, 0, 0, 1, 1L);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        for (long tick = 2L; tick <= 25L; tick++) {
            adapter.sendMovement(tick, 0, 0, 1, 0, 0, 0, 0, 1, tick);
        }
        releaseFirst.countDown();

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> sentTicks.size() == 25);

        List<Long> expected = new ArrayList<>();
        for (long tick = 1L; tick <= 25L; tick++) {
            expected.add(tick);
        }
        assertEquals(expected, sentTicks);
    }

    @Test
    @DisplayName("connect, send, and logout are serialized with zero concurrent races")
    void testConnectSendLogoutSerialized() throws Exception {
        AtomicBoolean connected = new AtomicBoolean(false);
        AtomicInteger activeOps = new AtomicInteger(0);
        AtomicInteger maxConcurrentOps = new AtomicInteger(0);
        CountDownLatch connectStarted = new CountDownLatch(1);
        CountDownLatch allowConnectFinish = new CountDownLatch(1);

        when(quicClient.isConnected()).thenAnswer(inv -> connected.get());
        when(quicClient.connect()).thenAnswer(inv -> {
            int c = activeOps.incrementAndGet();
            maxConcurrentOps.accumulateAndGet(c, Math::max);
            connectStarted.countDown();
            assertTrue(allowConnectFinish.await(2, TimeUnit.SECONDS));
            DefaultPromise<Void> promise = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
            promise.setSuccess(null);
            connected.set(true);
            activeOps.decrementAndGet();
            return promise;
        });
        when(quicClient.send(any())).thenAnswer(inv -> {
            int c = activeOps.incrementAndGet();
            maxConcurrentOps.accumulateAndGet(c, Math::max);
            assertTrue(connected.get(), "send must run after connect while connected");
            activeOps.decrementAndGet();
            return null;
        });
        doAnswer(inv -> {
            int c = activeOps.incrementAndGet();
            maxConcurrentOps.accumulateAndGet(c, Math::max);
            connected.set(false);
            activeOps.decrementAndGet();
            return null;
        }).when(quicClient).close();

        adapter.sendLogin("alice", "secret");
        assertTrue(connectStarted.await(2, TimeUnit.SECONDS));

        // Logout issued while connect() is still in flight must serialize close() cleanly after connect+send
        adapter.sendLogout();
        assertEquals(ClientConnectionState.DISCONNECTED, clientState.getConnectionState());

        allowConnectFinish.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(quicClient, times(1)).close());
        assertEquals(1, maxConcurrentOps.get());
    }

    @Test
    @DisplayName("onLoginResponse updates auth state without sending duplicate CharacterListRequest")
    void testNoDuplicateCharacterListRequestOnLogin() {
        adapter.onLoginResponse(new LoginResponse(true, 42L, "jwt-token", null));

        assertEquals(42L, clientState.getAccountId());
        assertEquals("jwt-token", clientState.getAuthToken());
        assertEquals(ClientConnectionState.AUTHENTICATED, clientState.getConnectionState());
        assertEquals("", clientState.getLastErrorMessage());
        verify(quicClient, never()).send(any());
    }

    @Test
    @DisplayName("RECONNECT_RESPONSE is registered and dispatched onto main thread")
    @SuppressWarnings("unchecked")
    void testReconnectResponseRegisteredAndRoutedOnMainThread() throws Exception {
        adapter.init();

        ArgumentCaptor<PacketHandler<ReconnectResponse>> handlerCaptor = ArgumentCaptor.forClass(PacketHandler.class);
        verify(quicClient).on(eq(MessageType.RECONNECT_RESPONSE), handlerCaptor.capture());

        ReconnectResponse resp = new ReconnectResponse(true, 77L, 555L, null);
        handlerCaptor.getValue().handle(null, resp);

        assertEquals(0L, clientState.getAccountId(), "Should not mutate ClientState before main-thread drain");

        taskScheduler.processForegroundTasks();

        assertEquals(77L, clientState.getAccountId());
        assertEquals(555L, clientState.getSelectedCharacterId());
        assertEquals(ClientConnectionState.IN_WORLD, clientState.getConnectionState());
        verify(stateService, times(1)).handlePacket(resp);
    }

    @Test
    @DisplayName("Rejected RECONNECT_RESPONSE sets error message and DISCONNECTED state")
    void testReconnectResponseFailure() {
        adapter.onReconnectResponse(new ReconnectResponse(false, 0L, 0L, "Expired session"));

        assertEquals(ClientConnectionState.DISCONNECTED, clientState.getConnectionState());
        assertEquals("Expired session", clientState.getLastErrorMessage());
    }

    @Test
    @DisplayName("PongMessage routes once through stateService without double pongListener invocation")
    void testPongRoutedOnceViaStateService() {
        PongMessage pong = new PongMessage(System.currentTimeMillis() - 15, "ping-1");

        adapter.onPacketReceived(pong);
        taskScheduler.processForegroundTasks();

        verify(stateService, times(1)).handlePacket(pong);
        assertTrue(clientState.getCurrentLatencyMs() >= 0L);
    }

    @Test
    @DisplayName("sendCharacterDelete does not fake-delete local characters and sets unsupported error message")
    void testSendCharacterDeleteDoesNotMutateCharacters() {
        CharacterSummary summary = new CharacterSummary(
                101L, "Hero", 1, (short) 1, (short) 0, 230, 10, (short) 0, (short) 1, (short) 0
        );
        clientState.updateCharacters(List.of(summary));
        clientState.setSelectedCharacterId(101L);

        adapter.sendCharacterDelete(101L);

        assertEquals(1, clientState.getCharacters().size());
        assertEquals(101L, clientState.getSelectedCharacterId());
        assertEquals("Character deletion is not yet supported by server", clientState.getLastErrorMessage());
        verify(quicClient, never()).send(any());
    }
}
