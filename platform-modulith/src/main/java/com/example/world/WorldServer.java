package com.example.world;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.DemoProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.world.handler.PlayerMovementRequestHandler;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = {"world", Environment.TEST})
@Requires(notEnv = {"gateway", "login", "lobby"})
public class WorldServer extends AbstractBackendServer {

  private final PlayerMovementRequestHandler movementHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;

  public WorldServer(
    DemoProperties properties,
    IndexedPacketRouter router,
    PlayerMovementRequestHandler movementHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    super("WORLD", properties.getWorld().getPort(), router);
    this.movementHandler = movementHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.PLAYER_MOVEMENT_REQUEST, movementHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);
    start();
  }
}