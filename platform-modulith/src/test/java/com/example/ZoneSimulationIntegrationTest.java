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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ZoneSimulationIntegrationTest extends BaseIntegrationTest {

  @Test
  @DisplayName("Persistence Checkpoint: Moving in Zone and disconnecting persists dirty state to PostgreSQL")
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

    client.send(new PlayerMovementRequest(100.5f, 64.0f, -250.5f, 5.0f, 0.0f, 0.0f, 45.0f, 0.0f, MovementFlags.FORWARD, 1L));
    moveFuture.get(5, TimeUnit.SECONDS);

    client.close();

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Optional<CharacterRecord> record = characterRepository.findById(1001L);
      assertThat(record).isPresent();
      assertThat(record.get().posX()).isEqualTo(100.5f);
      assertThat(record.get().posY()).isEqualTo(64.0f);
      assertThat(record.get().posZ()).isEqualTo(-250.5f);
    });
  }

  @Test
  @DisplayName("20 Hz Multiplayer AoI: Moving in Zone streams Spawn & Movement updates to nearby observers")
  void testMultiplayerSpatialAoIBroadcast() throws Exception {
    // 1. Client A (Warrior, charId=1001 at 100.0, 64.0, -250.5)
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED);
    client.send(new SelectCharacterRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.IN_WORLD);
    client.send(new JoinWorldRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() -> client.getState().getConnectionState() == ClientConnectionState.IN_ZONE);

    CompletableFuture<PlayerMovementResponse> clientAMove1 = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> clientAMove1.complete((PlayerMovementResponse) packet));
    client.send(new PlayerMovementRequest(100.0f, 64.0f, -250.5f, 0, 0, 0, 90.0f, 0, MovementFlags.IDLE, 1L));
    clientAMove1.get(5, TimeUnit.SECONDS);

    // 2. Client B (Mage, charId=1002 at 102.0, 64.0, -250.5 - 2m away in same AoI cell)
    QuicClient clientB = beanContext.createBean(QuicClient.class);
    clientB.registerDefaultHandlers();
    clientB.connect().sync();

    try {
      clientB.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED);
      clientB.send(new SelectCharacterRequest(clientB.getState().getAuthToken(), 1002L));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.IN_WORLD);
      clientB.send(new JoinWorldRequest(clientB.getState().getAuthToken(), 1002L));
      await().atMost(5, TimeUnit.SECONDS).until(() -> clientB.getState().getConnectionState() == ClientConnectionState.IN_ZONE);

      CompletableFuture<PlayerMovementResponse> clientBMove1 = new CompletableFuture<>();
      clientB.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> clientBMove1.complete((PlayerMovementResponse) packet));
      clientB.send(new PlayerMovementRequest(102.0f, 64.0f, -250.5f, 0, 0, 0, 0, 0, MovementFlags.IDLE, 1L));
      clientBMove1.get(5, TimeUnit.SECONDS);

      // 3. Client A moves slightly
      client.send(new PlayerMovementRequest(100.8f, 64.0f, -250.5f, 4.0f, 0.0f, 0.0f, 90.0f, 0.0f, MovementFlags.FORWARD, 2L));

      // 4. Verify Client B receives AoI delta with Client A's state
      await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
        assertThat(clientB.getState().getRemoteEntities()).containsKey(1001L);
        ClientState.RemoteEntityState warriorRemote = clientB.getState().getRemoteEntities().get(1001L);
        assertThat(warriorRemote.characterClass()).isEqualTo("WARRIOR");
      });
    } finally {
      clientB.close();
    }
  }
}