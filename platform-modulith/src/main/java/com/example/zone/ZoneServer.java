package com.example.zone;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.ZoneServerProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.zone.engine.ZoneSimulationEngine;
import com.example.zone.handler.PlayerMovementRequestHandler;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = "zone")
public class ZoneServer extends AbstractBackendServer {

  private final PlayerMovementRequestHandler movementHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;
  private final ZoneSimulationEngine simulationEngine;

  public ZoneServer(
    ZoneServerProperties properties,
    IndexedPacketRouter router,
    PlayerMovementRequestHandler movementHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler,
    ZoneSimulationEngine simulationEngine) {
    super("ZONE-" + properties.getZoneId(), properties.getHost(), properties.getPort(), router);
    this.movementHandler = movementHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
    this.simulationEngine = simulationEngine;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.PLAYER_MOVEMENT_REQUEST, movementHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);

    sessionDisconnectedHandler.onDisconnect(event -> {
      log.info("[ZONE-SERVER] Evicting entity for disconnected Session #{}", event.sessionId());
      simulationEngine.removeEntityBySession(event.sessionId());
    });

    start();
  }
}