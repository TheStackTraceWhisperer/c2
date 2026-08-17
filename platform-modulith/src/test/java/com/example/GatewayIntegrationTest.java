package com.example;

import com.example.client.QuicClient;
import com.example.client.state.ClientConnectionState;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ServiceType;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.ReconnectRequest;
import com.example.server.entity.AccountRecord;
import com.example.server.entity.CharacterRecord;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.server.repository.AccountRepository;
import com.example.server.repository.CharacterRepository;
import com.example.server.security.Argon2PasswordEncoder;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.ZoneTransferRequest;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MicronautTest
public class GatewayIntegrationTest {

  public static final long TEST_ACCOUNT_ID = 92668751L;
  public static final String TEST_USERNAME = "admin";
  public static final String TEST_PASSWORD = "secret123";

  @Inject
  private QuicClient client;

  @Inject
  private SessionDisconnectedHandler sessionDisconnectedHandler;

  @Inject
  private AccountRepository accountRepository;

  @Inject
  private CharacterRepository characterRepository;

  @Inject
  private Argon2PasswordEncoder passwordEncoder;

  @BeforeEach
  void setUp() throws Exception {
    seedDatabase();

    client.getState().reset();
    client.registerDefaultHandlers();
    client.connect().sync();
  }

  private void seedDatabase() {
    if (accountRepository.findByUsername(TEST_USERNAME).isEmpty()) {
      String encodedHash = passwordEncoder.encode(TEST_PASSWORD);
      accountRepository.save(new AccountRecord(
        TEST_ACCOUNT_ID,
        TEST_USERNAME,
        encodedHash,
        Instant.now(),
        Instant.now()
      ));
    }

    // Always reset character state to known baseline before each test run
    characterRepository.save(new CharacterRecord(
      TEST_ACCOUNT_ID, 1001L, "Warrior", 45, "WARRIOR", 1, 1, 100.0f, 64.0f, -250.5f, 90.0f, 0.0f
    ));
    characterRepository.save(new CharacterRecord(
      TEST_ACCOUNT_ID, 1002L, "Mage", 50, "WIZARD", 1, 1, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f
    ));
    characterRepository.save(new CharacterRecord(
      TEST_ACCOUNT_ID, 1003L, "Rogue", 30, "ROGUE", 2, 2, 550.0f, 70.0f, -990.0f, 180.0f, 0.0f
    ));
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
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));

    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    assertThat(client.getState().getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
    assertThat(client.getState().getAuthToken()).isNotBlank();

    client.send(new CharacterListRequest(
      client.getState().getAccountId(),
      client.getState().getAuthToken()
    ));

    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_LOBBY
    );

    assertThat(client.getState().getCharacters()).hasSize(3);
    assertThat(client.getState().getCharacters().get(0).name()).isEqualTo("Warrior");
    assertThat(client.getState().getCharacters().get(0).worldId()).isEqualTo(1);
    assertThat(client.getState().getCharacters().get(2).name()).isEqualTo("Rogue");
    assertThat(client.getState().getCharacters().get(2).worldId()).isEqualTo(2);
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
  @DisplayName("Teardown: Should broadcast SessionDisconnectedEvent to all 4 active backend streams on disconnect")
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

      client.send(new PlayerMovementRequest(0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
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
  @DisplayName("Full In-Game Journey: Login -> Character List -> Select Character -> Join World -> Move in Zone")
  void testCompleteInGameWorkflow() throws Exception {
    CompletableFuture<PlayerMovementResponse> movementFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      movementFuture.complete((PlayerMovementResponse) packet)
    );

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
    assertThat(client.getState().getSelectedCharacterId()).isEqualTo(1001L);

    client.send(new JoinWorldRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );

    client.send(new PlayerMovementRequest(100.0f, 64.0f, -250.5f, 90.0f, 0.0f));

    PlayerMovementResponse moveResp = movementFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(100.0f);
    assertThat(moveResp.serverTick()).isGreaterThan(0L);
  }

  @Test
  @DisplayName("Seamless Zone Transfer: Should migrate character between zone instances via WorldServer orchestration")
  void testSeamlessWorldTransfer() throws Exception {
    CompletableFuture<PlayerMovementResponse> move1Future = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> move1Future.complete((PlayerMovementResponse) packet));

    // 1. Authenticate, Select Character, Join World (Zone 1)
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
      client.getState().getCurrentZoneId() == 1
    );

    // 2. Move in Zone 1
    client.send(new PlayerMovementRequest(10.0f, 20.0f, 30.0f, 0.0f, 0.0f));
    PlayerMovementResponse move1 = move1Future.get(5, TimeUnit.SECONDS);
    assertThat(move1).isNotNull();
    assertThat(move1.x()).isEqualTo(10.0f);

    // 3. Request Zone Transfer to Zone 2 via World coordinator
    client.send(new ZoneTransferRequest(1001L, 2));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getCurrentZoneId() == 2
    );

    // 4. Move in Zone 2 seamlessly over the same client QUIC connection
    CompletableFuture<PlayerMovementResponse> move2Future = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> move2Future.complete((PlayerMovementResponse) packet));

    client.send(new PlayerMovementRequest(550.0f, 70.0f, -990.0f, 180.0f, 0.0f));
    PlayerMovementResponse move2 = move2Future.get(5, TimeUnit.SECONDS);

    assertThat(move2).isNotNull();
    assertThat(move2.x()).isEqualTo(550.0f);
    assertThat(client.isConnected()).isTrue();
  }

  @Test
  @DisplayName("Security Gate: Should drop Zone movement requests if character is not selected")
  void testSecurityGateBlocksWorldWithoutCharacterSelected() {
    CompletableFuture<PlayerMovementResponse> movementFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      movementFuture.complete((PlayerMovementResponse) packet)
    );

    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    client.send(new PlayerMovementRequest(10.0f, 20.0f, 30.0f, 0.0f, 0.0f));

    await()
      .during(2, TimeUnit.SECONDS)
      .atMost(3, TimeUnit.SECONDS)
      .until(() -> !movementFuture.isDone());
  }

  @Test
  @DisplayName("Rate Limiter: Should drop packet bursts exceeding token bucket capacity")
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
      client.send(new PlayerMovementRequest(i, 0, 0, 0, 0));
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
  @DisplayName("Safeguards: Gateway should reject and close connection when receiving oversized frame")
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

    await().atMost(3, TimeUnit.SECONDS).until(() -> !client.isConnected());
    assertThat(client.isConnected()).isFalse();
  }

  @Test
  @DisplayName("Resumption: Disconnected client should reconnect with JWT and re-enter world")
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

    client.send(new PlayerMovementRequest(50.0f, 10.0f, -100.0f, 0.0f, 0.0f));

    PlayerMovementResponse moveResp = moveFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(50.0f);
  }

  @Test
  @DisplayName("Persistence Checkpoint: Moving in Zone and disconnecting must persist coordinates to PostgreSQL")
  void testMovementPersistedToPostgresOnDisconnect() throws Exception {
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

    CompletableFuture<PlayerMovementResponse> moveFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> moveFuture.complete((PlayerMovementResponse) packet));

    // Move to unique position (x=777.0, y=88.0, z=-999.0)
    client.send(new PlayerMovementRequest(777.0f, 88.0f, -999.0f, 45.0f, 0.0f));
    moveFuture.get(5, TimeUnit.SECONDS);

    // Disconnect client to trigger dirty-state eviction flush
    client.close();

    // Verify coordinates are saved to PostgreSQL
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Optional<CharacterRecord> record = characterRepository.findById(1001L);
      assertThat(record).isPresent();
      assertThat(record.get().posX()).isEqualTo(777.0f);
      assertThat(record.get().posY()).isEqualTo(88.0f);
      assertThat(record.get().posZ()).isEqualTo(-999.0f);
    });
  }
}