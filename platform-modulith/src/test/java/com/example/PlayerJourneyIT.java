package com.example;

import com.example.client.state.ClientConnectionState;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.MovementFlags;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.login.dto.LoginRequest;
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

public class PlayerJourneyIT extends BaseIntegrationTest {

  @Test
  @DisplayName("Lifecycle: Authenticate -> Query Character List -> Populate FFXI Characters")
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
    assertThat(client.getState().getCharacters().get(0).name()).isEqualTo("ElvaanKnight");
    assertThat(client.getState().getCharacters().get(0).worldId()).isEqualTo(1);
    assertThat(client.getState().getCharacters().get(2).name()).isEqualTo("HumeRanger");
    assertThat(client.getState().getCharacters().get(2).worldId()).isEqualTo(2);
  }

  @Test
  @DisplayName("Lifecycle: Login -> Select Character -> Join FFXI S. San d'Oria (230) -> Move")
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
    assertThat(client.getState().getCurrentZoneId()).isEqualTo(230);

    client.send(new PlayerMovementRequest(0.2f, -2.0f, -10.0f, 1.0f, 0.0f, 0.0f, 90.0f, 0.0f, MovementFlags.FORWARD, 1L));

    PlayerMovementResponse moveResp = movementFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(0.2f);
    assertThat(moveResp.serverTick()).isGreaterThan(0L);
  }
}