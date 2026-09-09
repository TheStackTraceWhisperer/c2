package com.example;

import com.example.client.state.ClientConnectionState;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PingMessage;
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
  @DisplayName("Security: Gateway returns GatewayErrorResponse(UNAUTHENTICATED) and terminates connection when unauthenticated")
  void unauthenticatedPacketTriggersRejectionAndDisconnect() {
    CompletableFuture<GatewayErrorResponse> errorFuture = new CompletableFuture<>();

    client.on(MessageType.GATEWAY_ERROR_RESPONSE, (ctx, packet) ->
      errorFuture.complete((GatewayErrorResponse) packet)
    );

    client.send(new CharacterListRequest(999L, "fake-token"));

    await().atMost(3, TimeUnit.SECONDS).until(errorFuture::isDone);
    GatewayErrorResponse error = errorFuture.join();
    assertThat(error.errorCode()).isEqualTo(GatewayErrorResponse.ERROR_UNAUTHENTICATED);
    assertThat(error.rejectedServiceId()).isEqualTo(ServiceType.LOBBY);

    await().atMost(5, TimeUnit.SECONDS).until(() -> !client.isConnected());
    assertThat(client.isConnected()).isFalse();
  }

  @Test
  @DisplayName("Security: Gateway returns GatewayErrorResponse(INVALID_SESSION_STATE) and severs connection for out-of-state packets")
  void outOfStatePacketTriggersRejectionAndDisconnect() {
    CompletableFuture<GatewayErrorResponse> errorFuture = new CompletableFuture<>();
    client.on(MessageType.GATEWAY_ERROR_RESPONSE, (ctx, packet) ->
      errorFuture.complete((GatewayErrorResponse) packet)
    );

    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    // Client sends in-world action without selecting character
    client.send(new PlayerMovementRequest(10.0f, 20.0f, 30.0f, 0, 0, 0, 0, 0, MovementFlags.IDLE, 1L));

    await().atMost(3, TimeUnit.SECONDS).until(errorFuture::isDone);
    GatewayErrorResponse error = errorFuture.join();
    assertThat(error.errorCode()).isEqualTo(GatewayErrorResponse.ERROR_INVALID_SESSION_STATE);
    assertThat(error.rejectedServiceId()).isEqualTo(ServiceType.ZONE);

    await().atMost(3, TimeUnit.SECONDS).until(() -> !client.isConnected());
    assertThat(client.isConnected()).isFalse();
  }

  @Test
  @DisplayName("Rate Limiter: Gateway returns GatewayErrorResponse(RATE_LIMITED) and severs connection when limit exceeded")
  void rateLimitExceededTriggersRejectionAndDisconnect() throws Exception {
    CompletableFuture<GatewayErrorResponse> errorFuture = new CompletableFuture<>();
    client.on(MessageType.GATEWAY_ERROR_RESPONSE, (ctx, packet) ->
      errorFuture.complete((GatewayErrorResponse) packet)
    );

    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    // Flood packets to exhaust rate limiter token bucket
    for (int i = 0; i < 100; i++) {
      client.send(new PingMessage(System.currentTimeMillis(), "flood-" + i));
    }

    await().atMost(5, TimeUnit.SECONDS).until(errorFuture::isDone);
    GatewayErrorResponse error = errorFuture.join();
    assertThat(error.errorCode()).isEqualTo(GatewayErrorResponse.ERROR_RATE_LIMITED);

    await().atMost(5, TimeUnit.SECONDS).until(() -> !client.isConnected());
    assertThat(client.isConnected()).isFalse();
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