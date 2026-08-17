package com.example.zone;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.DemoProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.zone.engine.ZoneSimulationEngine;
import com.example.zone.handler.PlayerMovementRequestHandler;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = {"zone", Environment.TEST})
@Requires(notEnv = {"gateway", "login", "lobby", "world"})
public class ZoneServer extends AbstractBackendServer {

  private final PlayerMovementRequestHandler movementHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;
  private final ZoneSimulationEngine simulationEngine;

  public ZoneServer(
    DemoProperties properties,
    IndexedPacketRouter router,
    PlayerMovementRequestHandler movementHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler,
    ZoneSimulationEngine simulationEngine) {
    super("ZONE", properties.getZone().getPort(), router);
    this.movementHandler = movementHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
    this.simulationEngine = simulationEngine;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.PLAYER_MOVEMENT_REQUEST, movementHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);

    sessionDisconnectedHandler.onDisconnect(event -> {
      log.info("[ZONE-SERVER] Evicting and persisting entity for disconnected Session #{}", event.sessionId());
      simulationEngine.removeEntityBySession(event.sessionId());
    });

    start();
  }
}