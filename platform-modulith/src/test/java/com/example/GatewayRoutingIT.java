package com.example;

import com.example.client.state.ClientConnectionState;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.MovementFlags;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.ReconnectRequest;
import com.example.support.BaseIntegrationTest;
import com.example.world.dto.JoinWorldRequest;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class GatewayRoutingIT extends BaseIntegrationTest {

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

    client.send(new JoinWorldRequest(authToken));
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

    client.send(new JoinWorldRequest(authToken));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );

    CompletableFuture<PlayerMovementResponse> moveFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      moveFuture.complete((PlayerMovementResponse) packet)
    );

    client.send(new PlayerMovementRequest(66.1f, -1.0f, -40.0f, 2.0f, 0, 0, 180.0f, 0, MovementFlags.FORWARD, 1L));

    PlayerMovementResponse moveResp = moveFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isCloseTo(66.2f, org.assertj.core.data.Offset.offset(0.01f));
  }
}