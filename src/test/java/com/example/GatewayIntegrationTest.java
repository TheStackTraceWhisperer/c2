package com.example;

import com.example.client.QuicClient;
import com.example.client.state.ClientConnectionState;
import com.example.common.backend.protocol.SessionDisconnectedEvent;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ServiceType;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.world.dto.PlayerMovementRequest;
import com.example.world.dto.PlayerMovementResponse;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MicronautTest
public class GatewayIntegrationTest {

  @Inject
  private QuicClient client;

  @Inject
  private SessionDisconnectedHandler sessionDisconnectedHandler;

  @BeforeEach
  void setUp() throws Exception {
    client.getState().reset();
    client.registerDefaultHandlers();
    client.connect().sync();
  }

  @AfterEach
  void tearDown() {
    client.close();
  }

  @Test
  @DisplayName("Diagnostics: Should exchange Ping and Pong with LoginServer via Gateway")
  void testPingPong() throws Exception {
    CompletableFuture<PongMessage> pongFuture = new CompletableFuture<>();

    client.on(MessageType.PONG, (ctx, packet) -> pongFuture.complete((PongMessage) packet));
    client.send(new PingMessage(System.currentTimeMillis(), "PING"));

    PongMessage pong = pongFuture.get(5, TimeUnit.SECONDS);
    assertThat(pong).isNotNull();
    assertThat(pong.message()).isEqualTo("PONG");
  }

  @Test
  @DisplayName("Production Flow: Login -> Lobby -> Populate ClientState")
  void testFullClientWorkflow() {
    client.send(new LoginRequest("admin", "secret123"));

    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    assertThat(client.getState().getAccountId()).isEqualTo(92668751L);
    assertThat(client.getState().getAuthToken()).isNotBlank();

    client.send(new CharacterListRequest(
      client.getState().getAccountId(),
      client.getState().getAuthToken()
    ));

    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_LOBBY
    );

    assertThat(client.getState().getCharacters()).hasSize(2);
    assertThat(client.getState().getCharacters().get(0).name()).isEqualTo("Warrior");
    assertThat(client.getState().getCharacters().get(1).name()).isEqualTo("Mage");
  }

  @Test
  @DisplayName("Security Gate: Should drop Lobby requests if session is unauthenticated")
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
  @DisplayName("Teardown: Should broadcast SessionDisconnectedEvent to all 3 backend servers on client disconnect")
  void testSessionTeardownBroadcast() throws Exception {
    List<SessionDisconnectedEvent> receivedEvents = new CopyOnWriteArrayList<>();
    Consumer<SessionDisconnectedEvent> listener = receivedEvents::add;

    CompletableFuture<PlayerMovementResponse> moveFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      moveFuture.complete((PlayerMovementResponse) packet)
    );

    try {
      // 1. Authenticate (Stream 1: LoginServer)
      client.send(new LoginRequest("admin", "secret123"));
      await().atMost(5, TimeUnit.SECONDS).until(() ->
        client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
      );

      // 2. Select Character (Stream 2: LobbyServer)
      // Example in GatewayIntegrationTest
      client.send(new SelectCharacterRequest(
        client.getState().getAccountId(),
        client.getState().getAuthToken(),
        1001L
      ));
      await().atMost(5, TimeUnit.SECONDS).until(() ->
        client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
      );

      // 3. Move in World & Await response (Stream 3: WorldServer)
      client.send(new PlayerMovementRequest(0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
      moveFuture.get(5, TimeUnit.SECONDS);

      // 4. Attach listener right before closing so previous test noise is excluded
      sessionDisconnectedHandler.onDisconnect(listener);
      receivedEvents.clear();

      // 5. Abruptly close client connection
      client.close();

      // 6. Assert all 3 backend streams received the teardown for this session
      await().atMost(5, TimeUnit.SECONDS).until(() -> receivedEvents.size() >= 3);

      long activeSessionId = receivedEvents.get(0).sessionId();
      List<SessionDisconnectedEvent> sessionEvents = receivedEvents.stream()
        .filter(e -> e.sessionId() == activeSessionId)
        .toList();

      assertThat(sessionEvents)
        .hasSize(3)
        .allMatch(event -> event.timestamp() > 0);

    } finally {
      sessionDisconnectedHandler.removeListener(listener);
    }
  }

  @Test
  @DisplayName("Full In-Game Journey: Login -> Character List -> Select Character -> Move in World")
  void testCompleteInGameWorkflow() throws Exception {
    CompletableFuture<PlayerMovementResponse> movementFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      movementFuture.complete((PlayerMovementResponse) packet)
    );

    // 1. Login
    client.send(new LoginRequest("admin", "secret123"));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    // 2. Select Character
    client.send(new SelectCharacterRequest(
      client.getState().getAccountId(),
      client.getState().getAuthToken(),
      1001L
    ));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );
    assertThat(client.getState().getSelectedCharacterId()).isEqualTo(1001L);

    // 3. Move in World
    client.send(new PlayerMovementRequest(100.0f, 64.0f, -250.5f, 90.0f, 0.0f));

    PlayerMovementResponse moveResp = movementFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(100.0f);
    assertThat(moveResp.serverTick()).isGreaterThan(0L);
  }

  @Test
  @DisplayName("Security Gate: Should drop World movement requests if character is not selected")
  void testSecurityGateBlocksWorldWithoutCharacterSelected() {
    CompletableFuture<PlayerMovementResponse> movementFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      movementFuture.complete((PlayerMovementResponse) packet)
    );

    // 1. Authenticate but DO NOT select a character
    client.send(new LoginRequest("admin", "secret123"));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    // 2. Attempt direct World movement
    client.send(new PlayerMovementRequest(10.0f, 20.0f, 30.0f, 0.0f, 0.0f));

    // 3. Ensure no response is returned
    await()
      .during(2, TimeUnit.SECONDS)
      .atMost(3, TimeUnit.SECONDS)
      .until(() -> !movementFuture.isDone());
  }

  @Test
  @DisplayName("Rate Limiter: Should drop packet bursts exceeding token bucket capacity")
  void testRateLimiterDropsExcessiveBursts() throws Exception {
    // 1. Authenticate & Select Character
    client.send(new LoginRequest("admin", "secret123"));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    // Example in GatewayIntegrationTest
    client.send(new SelectCharacterRequest(
      client.getState().getAccountId(),
      client.getState().getAuthToken(),
      1001L
    ));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );

    // 2. Count acknowledged responses
    java.util.concurrent.atomic.AtomicInteger acknowledgedMoves = new java.util.concurrent.atomic.AtomicInteger(0);
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      acknowledgedMoves.incrementAndGet()
    );

    // 3. Blast 100 packets instantly (Capacity is configured to 50 in application.yml)
    int packetsSent = 100;
    for (int i = 0; i < packetsSent; i++) {
      client.send(new PlayerMovementRequest(i, 0, 0, 0, 0));
    }

    // 4. Await processing
    await()
      .during(1, TimeUnit.SECONDS)
      .atMost(2, TimeUnit.SECONDS)
      .until(() -> true);

    // Acknowledged packets must be capped at capacity (<= 50)
    assertThat(acknowledgedMoves.get())
      .isGreaterThan(0)
      .isLessThanOrEqualTo(50);
  }

  @Test
  @DisplayName("Safeguards: Gateway should reject and close connection when receiving oversized frame")
  void testOversizedPayloadRejectedAtEdge() throws Exception {
    // 1. Authenticate normally
    client.send(new LoginRequest("admin", "secret123"));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    // 2. Prepare an oversized framed buffer (20 KB > 16 KB MAX_PAYLOAD_SIZE)
    byte[] hugePayload = new byte[20 * 1024];
    ByteBuf rawFrame = Unpooled.buffer(4 + 4 + hugePayload.length);

    int frameLength = 4 + hugePayload.length;
    rawFrame.writeInt(frameLength);
    rawFrame.writeShort(ServiceType.AUTH);
    rawFrame.writeShort(MessageType.PING);
    rawFrame.writeBytes(hugePayload);

    // 3. Inject raw frame via client
    client.sendRaw(rawFrame);

    // 4. Assert Gateway detects violation and terminates the client connection
    await().atMost(3, TimeUnit.SECONDS).until(() -> !client.isConnected());

    assertThat(client.isConnected()).isFalse();
  }

  @Test
  @DisplayName("Resumption: Disconnected client should reconnect and resume in-world session")
  void testSessionReconnectionAndResumption() throws Exception {
    // 1. Initial login & character selection
    client.send(new LoginRequest("admin", "secret123"));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    long accountId = client.getState().getAccountId();
    String authToken = client.getState().getAuthToken();

    // Example in GatewayIntegrationTest
    client.send(new SelectCharacterRequest(
      client.getState().getAccountId(),
      client.getState().getAuthToken(),
      1001L
    ));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );

    // 2. Abrupt disconnect (simulating network blip)
    client.close();
    assertThat(client.isConnected()).isFalse();

    // 3. Connect a new QUIC connection (new snowflake session ID allocated at gateway)
    client.connect().sync();
    assertThat(client.isConnected()).isTrue();

    // 4. Send ReconnectRequest with bearer token
    client.reconnect(accountId, authToken);
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );

    // 5. Send World movement on the restored session to verify Gateway permissions are active
    CompletableFuture<PlayerMovementResponse> moveFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      moveFuture.complete((PlayerMovementResponse) packet)
    );

    client.send(new PlayerMovementRequest(50.0f, 10.0f, -100.0f, 0.0f, 0.0f));

    PlayerMovementResponse moveResp = moveFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(50.0f);
  }
}