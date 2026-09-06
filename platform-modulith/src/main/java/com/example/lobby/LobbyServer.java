package com.example.lobby;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.LobbyServerProperties;
import com.example.lobby.handler.CharacterListRequestHandler;
import com.example.lobby.handler.CreateCharacterRequestHandler;
import com.example.lobby.handler.SelectCharacterRequestHandler;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = "lobby")
public class LobbyServer extends AbstractBackendServer {

  private final CharacterListRequestHandler characterListHandler;
  private final SelectCharacterRequestHandler selectCharacterHandler;
  private final CreateCharacterRequestHandler createCharacterHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;

  public LobbyServer(
    LobbyServerProperties properties,
    IndexedPacketRouter router,
    CharacterListRequestHandler characterListHandler,
    SelectCharacterRequestHandler selectCharacterHandler,
    CreateCharacterRequestHandler createCharacterHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    super("LOBBY", properties.getHost(), properties.getPort(), router);
    this.characterListHandler = characterListHandler;
    this.selectCharacterHandler = selectCharacterHandler;
    this.createCharacterHandler = createCharacterHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.CHARACTER_LIST_REQUEST, characterListHandler);
    router.register(MessageType.SELECT_CHARACTER_REQUEST, selectCharacterHandler);
    router.register(MessageType.CREATE_CHARACTER_REQUEST, createCharacterHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);
    start();
  }
}