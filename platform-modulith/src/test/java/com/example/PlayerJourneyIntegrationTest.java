package com.example;

import com.example.client.state.ClientConnectionState;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.MovementFlags;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
import com.example.support.BaseIntegrationTest;
import com.example.world.WorldServer;
import com.example.world.dto.JoinWorldRequest;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class PlayerJourneyIntegrationTest extends BaseIntegrationTest {

  @Inject
  private WorldServer worldServer;

  @Test
  @DisplayName("Lifecycle: Authenticate -> Query Character List -> Populate State")
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
  @DisplayName("Lifecycle: Login -> Select Character -> Join World -> Move in Authoritative Zone")
  void testCompleteInGameWorkflow() throws Exception {
    CompletableFuture<PlayerMovementResponse> movementFuture = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) ->
      movementFuture.complete((PlayerMovementResponse) packet)
    );

    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    client.send(new SelectCharacterRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_WORLD
    );
    assertThat(client.getState().getSelectedCharacterId()).isEqualTo(1001L);

    client.send(new JoinWorldRequest(client.getState().getAuthToken(), 1001L));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );

    client.send(new PlayerMovementRequest(100.2f, 64.0f, -250.5f, 4.0f, 0.0f, 0.0f, 90.0f, 0.0f, MovementFlags.FORWARD, 1L));

    PlayerMovementResponse moveResp = movementFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(100.2f);
    assertThat(moveResp.serverTick()).isGreaterThan(0L);
  }

  @Test
  @DisplayName("Lifecycle: Server-Authoritative Zone Transfer and Routing Handoff")
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
    client.send(new PlayerMovementRequest(100.1f, 64.0f, -250.5f, 2.0f, 0.0f, 0.0f, 0.0f, 0.0f, MovementFlags.FORWARD, 1L));
    PlayerMovementResponse move1 = move1Future.get(5, TimeUnit.SECONDS);
    assertThat(move1).isNotNull();
    assertThat(move1.x()).isEqualTo(100.1f);

    // 3. Server orchestrates authoritative zone transfer to Zone 2
    worldServer.transferPlayer(client.getState().getAccountId(), 1001L, 2);

    // Client receives ZoneTransferNotification and adopts Zone 2 state
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getCurrentZoneId() == 2 && client.getState().getX() == 550.0f
    );

    // 4. Client moves in Zone 2
    CompletableFuture<PlayerMovementResponse> move2Future = new CompletableFuture<>();
    client.on(MessageType.PLAYER_MOVEMENT_RESPONSE, (ctx, packet) -> move2Future.complete((PlayerMovementResponse) packet));

    client.send(new PlayerMovementRequest(550.2f, 70.0f, -990.0f, 4.0f, 0.0f, 0.0f, 180.0f, 0.0f, MovementFlags.FORWARD, 2L));
    PlayerMovementResponse move2 = move2Future.get(5, TimeUnit.SECONDS);

    assertThat(move2).isNotNull();
    assertThat(move2.x()).isEqualTo(550.2f);
    assertThat(client.isConnected()).isTrue();
  }
}