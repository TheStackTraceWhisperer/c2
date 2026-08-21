package com.example;

import com.example.client.state.ClientConnectionState;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.MovementFlags;
import com.example.common.protocol.ServiceType;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
import com.example.support.BaseIntegrationTest;
import com.example.world.dto.JoinWorldRequest;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class GatewaySecurityIT extends BaseIntegrationTest {

  @Test
  @DisplayName("Security: Gateway drops Lobby requests if session is unauthenticated")
  void testSecurityGateBlocksUnauthenticatedLobbyRequest() {
    CompletableFuture<CharacterListResponse> characterListFuture = new CompletableFuture<>();

    client.on(MessageType.CHARACTER_LIST_RESPONSE, (ctx, packet) ->
      characterListFuture.complete((CharacterListResponse) packet)
    );

    client.send(new CharacterListRequest(999L, "fake-token"));

    await()
      .during(2, TimeUnit.SECONDS)
      .atMost(3, TimeUnit.SECONDS)
      .until(() -> !characterListFuture.isDone() && client.getState().getCharacters().isEmpty());
  }

  @Test
  @DisplayName("Security: Gateway drops Zone movement requests if character is not selected")
  void testSecurityGateBlocksWorldWithoutCharacterSelected() {
    CompletableFuture<PlayerMovementResponse> movementFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      movementFuture.complete((PlayerMovementResponse) packet)
    );

    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    client.send(new PlayerMovementRequest(10.0f, 20.0f, 30.0f, 0, 0, 0, 0, 0, MovementFlags.IDLE, 1L));

    await()
      .during(2, TimeUnit.SECONDS)
      .atMost(3, TimeUnit.SECONDS)
      .until(() -> !movementFuture.isDone());
  }

  @Test
  @DisplayName("Rate Limiter: Gateway drops packet bursts exceeding token bucket capacity")
  void testRateLimiterDropsExcessiveBursts() throws Exception {
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    client.send(new SelectCharacterRequest(
      client.getState().getAuthToken(),
      1001L
    ));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );

    client.send(new JoinWorldRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );

    AtomicInteger acknowledgedMoves = new AtomicInteger(0);
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      acknowledgedMoves.incrementAndGet()
    );

    int packetsSent = 100;
    for (int i = 0; i < packetsSent; i++) {
      client.send(new PlayerMovementRequest(100.0f + (i * 0.01f), 64.0f, -250.5f, 0, 0, 0, 0, 0, MovementFlags.IDLE, i));
    }

    await()
      .during(1, TimeUnit.SECONDS)
      .atMost(2, TimeUnit.SECONDS)
      .until(() -> true);

    assertThat(acknowledgedMoves.get())
      .isGreaterThan(0)
      .isLessThanOrEqualTo(55);
  }

  @Test
  @DisplayName("Safeguards: Gateway rejects and closes QuicChannel when receiving oversized frame")
  void testOversizedPayloadRejectedAtEdge() throws Exception {
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    byte[] hugePayload = new byte[20 * 1024];
    ByteBuf rawFrame = Unpooled.buffer(4 + 4 + hugePayload.length);

    int frameLength = 4 + hugePayload.length;
    rawFrame.writeInt(frameLength);
    rawFrame.writeShort(ServiceType.AUTH);
    rawFrame.writeShort(MessageType.PING);
    rawFrame.writeBytes(hugePayload);

    client.sendRaw(rawFrame);

    await().atMost(5, TimeUnit.SECONDS).until(() -> !client.isConnected());
    assertThat(client.isConnected()).isFalse();
  }
}