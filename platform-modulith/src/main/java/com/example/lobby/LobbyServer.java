package com.example.lobby;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.DemoProperties;
import com.example.lobby.handler.CharacterListRequestHandler;
import com.example.lobby.handler.SelectCharacterRequestHandler;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
public class LobbyServer extends AbstractBackendServer {

  private final CharacterListRequestHandler characterListHandler;
  private final SelectCharacterRequestHandler selectCharacterHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;

  public LobbyServer(
    DemoProperties properties,
    IndexedPacketRouter router,
    CharacterListRequestHandler characterListHandler,
    SelectCharacterRequestHandler selectCharacterHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    super("LOBBY", properties.getLobby().getPort(), router);
    this.characterListHandler = characterListHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
    this.selectCharacterHandler = selectCharacterHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.CHARACTER_LIST_REQUEST, characterListHandler);
    router.register(MessageType.SELECT_CHARACTER_REQUEST, selectCharacterHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);
    start();
  }
}