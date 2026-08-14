package com.example;

import com.example.client.QuicClient;
import com.example.client.state.ClientConnectionState;
import com.example.common.backend.protocol.SessionDisconnectedEvent;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MessageType;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.login.dto.LoginRequest;
import com.example.server.handler.SessionDisconnectedHandler;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
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
  @DisplayName("Teardown: Should broadcast SessionDisconnectedEvent to backend servers on client disconnect")
  void testSessionTeardownBroadcast() {
    List<SessionDisconnectedEvent> receivedEvents = new CopyOnWriteArrayList<>();
    Consumer<SessionDisconnectedEvent> listener = receivedEvents::add;
    sessionDisconnectedHandler.onDisconnect(listener);

    try {
      // 1. Establish streams to both LoginServer and LobbyServer
      client.send(new LoginRequest("admin", "secret123"));

      await().atMost(5, TimeUnit.SECONDS).until(() ->
        client.getState().getConnectionState() == ClientConnectionState.AUTHENTICATED
      );

      client.send(new CharacterListRequest(
        client.getState().getAccountId(),
        client.getState().getAuthToken()
      ));

      await().atMost(5, TimeUnit.SECONDS).until(() ->
        client.getState().getConnectionState() == ClientConnectionState.IN_LOBBY
      );

      // 2. Abruptly close client connection
      client.close();

      // 3. Assert both backend streams (LoginServer & LobbyServer) received the disconnect event
      await().atMost(5, TimeUnit.SECONDS).until(() -> receivedEvents.size() >= 2);

      assertThat(receivedEvents)
        .hasSize(2)
        .allMatch(event -> event.sessionId() > 0 && event.timestamp() > 0);

    } finally {
      sessionDisconnectedHandler.removeListener(listener);
    }
  }
}