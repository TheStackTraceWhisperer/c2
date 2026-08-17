package com.example.lobby;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.DemoProperties;
import com.example.lobby.handler.CharacterListRequestHandler;
import com.example.lobby.handler.SelectCharacterRequestHandler;
import com.example.lobby.registry.WorldRegistry;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = {"lobby", Environment.TEST})
@Requires(notEnv = {"gateway", "login", "world", "zone"})
public class LobbyServer extends AbstractBackendServer {

  private final DemoProperties properties;
  private final WorldRegistry worldRegistry;
  private final CharacterListRequestHandler characterListHandler;
  private final SelectCharacterRequestHandler selectCharacterHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;

  public LobbyServer(
    DemoProperties properties,
    WorldRegistry worldRegistry,
    IndexedPacketRouter router,
    CharacterListRequestHandler characterListHandler,
    SelectCharacterRequestHandler selectCharacterHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    super("LOBBY", properties.getLobby().getPort(), router);
    this.properties = properties;
    this.worldRegistry = worldRegistry;
    this.characterListHandler = characterListHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
    this.selectCharacterHandler = selectCharacterHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.CHARACTER_LIST_REQUEST, characterListHandler);
    router.register(MessageType.SELECT_CHARACTER_REQUEST, selectCharacterHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);

    // Seed available realm coordinates into the registry
    int worldPort = properties.getWorld().getPort();
    String worldHost = properties.getWorld().getHostname();
    worldRegistry.registerWorld(1, worldHost, worldPort);
    worldRegistry.registerWorld(2, worldHost, worldPort);

    start();
  }
}