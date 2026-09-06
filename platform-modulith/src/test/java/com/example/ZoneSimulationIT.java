package com.example;

import com.example.client.QuicClient;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.MovementFlags;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
import com.example.server.entity.CharacterRecord;
import com.example.support.BaseIntegrationTest;
import com.example.world.dto.JoinWorldRequest;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import io.micronaut.context.BeanContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ZoneSimulationIT extends BaseIntegrationTest {

  @Inject
  private BeanContext beanContext;

  @Test
  @DisplayName("Persistence Checkpoint: Moving in S. San d'Oria (230) persists dirty state to PostgreSQL")
  void testMovementPersistedToPostgresOnDisconnect() throws Exception {
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    client.send(new SelectCharacterRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );

    client.send(new JoinWorldRequest(client.getState().getAuthToken()));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );
    assertThat(client.getState().getCurrentZoneId()).isEqualTo(230);

    CompletableFuture<PlayerMovementResponse> moveFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> moveFuture.complete((PlayerMovementResponse) packet));

    client.send(new PlayerMovementRequest(66.5f, -1.0f, -40.0f, 1.0f, 0.0f, 0.0f, 180.0f, 0.0f, MovementFlags.FORWARD, 1L));
    moveFuture.get(5, TimeUnit.SECONDS);

    client.close();

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Optional<CharacterRecord> record = characterRepository.findById(1001L);
      assertThat(record).isPresent();
      assertThat(record.get().posX()).isEqualTo(66.5f);
      assertThat(record.get().posY()).isEqualTo(-1.0f);
      assertThat(record.get().posZ()).isEqualTo(-40.0f);
      assertThat(record.get().zoneId()).isEqualTo(230);
    });
  }

  @Test
  @DisplayName("20 Hz Multiplayer AoI: Moving in S. San d'Oria streams updates to distinct account observer")
  void testMultiplayerSpatialAoIBroadcast() throws Exception {
    // 1. Client A (Account 1: admin, Character 1001: Elvaan WAR at 66.0, -1.0, -40.0)
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED);
    client.send(new SelectCharacterRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.IN_WORLD);
    client.send(new JoinWorldRequest(client.getState().getAuthToken()));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.IN_ZONE);

    CompletableFuture<PlayerMovementResponse> clientAMove1 = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> clientAMove1.complete((PlayerMovementResponse) packet));
    client.send(new PlayerMovementRequest(66.0f, -1.0f, -40.0f, 0, 0, 0, 180.0f, 0, MovementFlags.IDLE, 1L));
    clientAMove1.get(5, TimeUnit.SECONDS);

    // 2. Client B (Account 2: player2, Character 1002: Tarutaru BLM at 68.0, -1.0, -40.0 - 2m away in same AoI cell)
    QuicClient clientB = beanContext.createBean(QuicClient.class);
    clientB.registerDefaultHandlers();
    clientB.connect().sync();

    try {
      clientB.send(new LoginRequest(TEST_USERNAME_2, TEST_PASSWORD_2));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED);
      clientB.send(new SelectCharacterRequest(clientB.getState().getAuthToken(), 1002L));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.IN_WORLD);
      clientB.send(new JoinWorldRequest(clientB.getState().getAuthToken()));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.IN_ZONE);

      CompletableFuture<PlayerMovementResponse> clientBMove1 = new CompletableFuture<>();
      clientB.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> clientBMove1.complete((PlayerMovementResponse) packet));
      clientB.send(new PlayerMovementRequest(68.0f, -1.0f, -40.0f, 0, 0, 0, 0, 0, MovementFlags.IDLE, 1L));
      clientBMove1.get(5, TimeUnit.SECONDS);

      // 3. Client A moves slightly within Client B's AoI
      client.send(new PlayerMovementRequest(66.8f, -1.0f, -40.0f, 2.0f, 0.0f, 0.0f, 180.0f, 0.0f, MovementFlags.FORWARD, 2L));

      // 4. Verify Client B receives AoI delta with Client A's state
      await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
        assertThat(clientB.getState().getRemoteEntities()).containsKey(1001L);
        ClientState.RemoteEntityState warriorRemote = clientB.getState().getRemoteEntities().get(1001L);
        assertThat(warriorRemote.mainJobId()).isEqualTo((short) 1);
        assertThat(warriorRemote.name()).isEqualTo("ElvaanKnight");
      });
    } finally {
      clientB.close();
    }
  }

  @Test
  @DisplayName("Identity Isolation: Concurrent sessions independently resolve authoritative character context without spoofing")
  void testConcurrentMultiPlayerSessionCharacterIsolation() throws Exception {
    // 1. Setup Session A (Account 1 -> Character 1001)
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED);
    client.send(new SelectCharacterRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.IN_WORLD);
    client.send(new JoinWorldRequest(client.getState().getAuthToken()));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.IN_ZONE);

    // 2. Setup Session B (Account 2 -> Character 1002)
    QuicClient clientB = beanContext.createBean(QuicClient.class);
    clientB.registerDefaultHandlers();
    clientB.connect().sync();

    try {
      clientB.send(new LoginRequest(TEST_USERNAME_2, TEST_PASSWORD_2));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED);
      clientB.send(new SelectCharacterRequest(clientB.getState().getAuthToken(), 1002L));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.IN_WORLD);
      clientB.send(new JoinWorldRequest(clientB.getState().getAuthToken()));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.IN_ZONE);

      CompletableFuture<PlayerMovementResponse> clientARespFuture = new CompletableFuture<>();
      client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> clientARespFuture.complete((PlayerMovementResponse) packet));

      CompletableFuture<PlayerMovementResponse> clientBRespFuture = new CompletableFuture<>();
      clientB.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> clientBRespFuture.complete((PlayerMovementResponse) packet));

      // 3. Dispatch movements without any client-asserted character IDs in the payload
      client.send(new PlayerMovementRequest(66.0f, -1.0f, -40.0f, 0, 0, 0, 180.0f, 0, MovementFlags.IDLE, 1L));
      clientB.send(new PlayerMovementRequest(68.0f, -1.0f, -40.0f, 0, 0, 0, 0, 0, MovementFlags.IDLE, 1L));

      PlayerMovementResponse respA = clientARespFuture.get(5, TimeUnit.SECONDS);
      PlayerMovementResponse respB = clientBRespFuture.get(5, TimeUnit.SECONDS);

      // 4. Assert responses strictly reflect each session's authoritative context
      assertThat(respA.characterId()).isEqualTo(1001L);
      assertThat(respB.characterId()).isEqualTo(1002L);

      // Verify session states remain strictly isolated
      assertThat(client.getState().getSelectedCharacterId()).isEqualTo(1001L);
      assertThat(clientB.getState().getSelectedCharacterId()).isEqualTo(1002L);
    } finally {
      clientB.close();
    }
  }
}