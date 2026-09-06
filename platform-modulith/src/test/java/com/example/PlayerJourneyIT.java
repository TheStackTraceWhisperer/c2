package com.example;

import com.example.client.QuicClient;
import com.example.client.state.ClientConnectionState;
import com.example.common.protocol.JobId;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.MovementFlags;
import com.example.lobby.dto.*;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.support.BaseIntegrationTest;
import com.example.world.dto.JoinWorldRequest;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import io.micronaut.context.BeanContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class PlayerJourneyIT extends BaseIntegrationTest {

  @Inject
  private BeanContext beanContext;

  @Test
  @DisplayName("Concurrency Guard: Concurrent login attempt with same account is rejected")
  void testConcurrentAccountLoginRejected() throws Exception {
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    QuicClient client2 = beanContext.createBean(QuicClient.class);
    client2.registerDefaultHandlers();
    client2.connect().sync();

    try {
      CompletableFuture<LoginResponse> duplicateLoginFuture = new CompletableFuture<>();
      client2.on(MessageType.LOGIN_RESPONSE, (ctx, packet) ->
        duplicateLoginFuture.complete((LoginResponse) packet)
      );

      client2.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));

      LoginResponse response = duplicateLoginFuture.get(5, TimeUnit.SECONDS);
      assertThat(response.success()).isFalse();
      assertThat(response.errorMessage()).isEqualTo("Account is already logged in");
    } finally {
      client2.close();
    }
  }

  @Test
  @DisplayName("Lifecycle: Authenticate -> Create Windurst Character -> Query Character List")
  void testCharacterCreationAndList() throws Exception {
    client.send(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
    );

    String authToken = client.getState().getAuthToken();

    CompletableFuture<CreateCharacterResponse> createFuture = new CompletableFuture<>();
    client.on(MessageType.CREATE_CHARACTER_RESPONSE, (ctx, packet) ->
      createFuture.complete((CreateCharacterResponse) packet)
    );

    client.send(new CreateCharacterRequest(
      authToken,
      1, // World 1 (Asura)
      "MithraThief",
      (short) 7, // Mithra
      (short) 1, // Female
      (short) 1, (short) 1, (short) 1,
      (short) 2, // Federation of Windurst
      JobId.THIEF
    ));

    CreateCharacterResponse createResp = createFuture.get(5, TimeUnit.SECONDS);
    assertThat(createResp.success()).isTrue();
    assertThat(createResp.characterId()).isGreaterThan(0L);

    client.send(new CharacterListRequest(client.getState().getAccountId(), authToken));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_LOBBY
    );

    assertThat(client.getState().getCharacters()).hasSize(3); // Account 1 has 2 seeded + 1 created = 3
    CharacterSummary created = client.getState().getCharacters().stream()
      .filter(c -> c.name().equals("MithraThief"))
      .findFirst().orElseThrow();

    assertThat(created.nationId()).isEqualTo((short) 2);
    assertThat(created.mainJobId()).isEqualTo(JobId.THIEF);
    assertThat(created.zoneId()).isEqualTo(238); // Windurst Waters
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

    client.send(new JoinWorldRequest(client.getState().getAuthToken()));
    await().atMost(5, TimeUnit.SECONDS).until(() ->
      client.getState().getConnectionState() == ClientConnectionState.IN_ZONE
    );
    assertThat(client.getState().getCurrentZoneId()).isEqualTo(230);

    client.send(new PlayerMovementRequest(66.2f, -1.0f, -40.0f, 1.0f, 0.0f, 0.0f, 180.0f, 0.0f, MovementFlags.FORWARD, 1L));

    PlayerMovementResponse moveResp = movementFuture.get(5, TimeUnit.SECONDS);
    assertThat(moveResp).isNotNull();
    assertThat(moveResp.x()).isEqualTo(66.2f);
    assertThat(moveResp.serverTick()).isGreaterThan(0L);
  }
}