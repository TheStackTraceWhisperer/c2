package com.example;

import com.example.client.state.ClientConnectionState;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.MovementFlags;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.ReconnectRequest;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.support.BaseIntegrationTest;
import com.example.world.dto.JoinWorldRequest;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class GatewayRoutingTest extends BaseIntegrationTest {

  @Inject
  private SessionDisconnectedHandler sessionDisconnectedHandler;

  @Test
  @DisplayName("Transport: Client exchanges Ping and Pong with LoginServer via Gateway")
  void testPingPong() throws Exception {
    CompletableFuture<PongMessage> pongFuture = new CompletableFuture<>();

    client.on(MessageType.PONG, (ctx, packet) -> pongFuture.complete((PongMessage) packet));
    client.send(new PingMessage(System.currentTimeMillis(), "PING"));

    PongMessage pong = pongFuture.get(5, TimeUnit.SECONDS);
    assertThat(pong).isNotNull();
    assertThat(pong.message()).isEqualTo("PONG");
  }

  @Test
  @DisplayName("Teardown: Gateway broadcasts SessionDisconnectedEvent to all active backend streams on disconnect")
  void testSessionTeardownBroadcast() throws Exception {
    List<SessionDisconnectedEvent> receivedEvents = new CopyOnWriteArrayList<>();
    Consumer<SessionDisconnectedEvent> listener = receivedEvents::add;

    CompletableFuture<PlayerMovementResponse> moveFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      moveFuture.complete((PlayerMovementResponse) packet)
    );

    try {
      client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
      await().atMost(5, TimeUnit.SECONDS).until(() ->
        client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
      );

      client.send(new SelectCharacterRequest(client.getState().getAuthToken(), 1001L));
      await().atMost(5, TimeUnit.SECONDS).until(() ->
        client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
      );

      client.send(new JoinWorldRequest(client.getState().getAuthToken(), 1001L));
      await().atMost(5, TimeUnit.SECONDS).until(() ->
        client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
      );

      client.send(new PlayerMovementRequest(100.0f, 64.0f, -250.5f, 0, 0, 0, 0, 0, MovementFlags.IDLE, 1L));
      moveFuture.get(5, TimeUnit.SECONDS);

      sessionDisconnectedHandler.onDisconnect(listener);
      receivedEvents.clear();

      client.close();

      await().atMost(5, TimeUnit.SECONDS).until(() -> receivedEvents.size() >= 4);

      long activeSessionId = receivedEvents.get(0).sessionId();
      List<SessionDisconnectedEvent> sessionEvents = receivedEvents.stream()
        .filter(e -> e.sessionId() == activeSessionId)
        .toList();

      assertThat(sessionEvents)
        .hasSize(4)
        .allMatch(event -> event.timestamp() > 0);

    } finally {
      sessionDisconnectedHandler.removeListener(listener);
    }
  }

  @Test
  @DisplayName("Resumption: Disconnected client reconnects with JWT token and resumes world state")
  void testSessionReconnectionAndResumption() throws Exception {
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    String authToken = client.getState().getAuthToken();

    client.send(new SelectCharacterRequest(authToken, 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );

    client.send(new JoinWorldRequest(authToken, 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );

    client.close();
    assertThat(client.isConnected()).isFalse();

    client.connect().sync();
    assertThat(client.isConnected()).isTrue();

    client.send(new ReconnectRequest(authToken));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    client.send(new SelectCharacterRequest(authToken, 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );

    client.send(new JoinWorldRequest(authToken, 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );

    CompletableFuture<PlayerMovementResponse> moveFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      moveFuture.complete((PlayerMovementResponse) packet)
    );

    client.send(new PlayerMovementRequest(100.1f, 64.0f, -250.5f, 2.0f, 0, 0, 0, 0, MovementFlags.FORWARD, 1L));

    PlayerMovementResponse moveResp = moveFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(100.1f);
  }
}