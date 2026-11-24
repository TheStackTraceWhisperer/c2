package com.example.zone;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.protocol.MessageType;
import com.example.config.ZoneServerProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import com.example.zone.engine.ZoneSimulationEngine;
import com.example.zone.handler.PlayerMovementRequestHandler;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Context
@Requires(env = "zone")
public class ZoneServer extends AbstractBackendServer {

  private final PlayerMovementRequestHandler movementHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;
  private final ZoneSimulationEngine simulationEngine;

  public ZoneServer(
    ZoneServerProperties properties,
    PlayerMovementRequestHandler movementHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler,
    ZoneSimulationEngine simulationEngine) {
    super("ZONE-" + properties.getZoneId(), properties.getHost(), properties.getPort());
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

  @Override
  protected void warmupCodecs() {
    super.warmupCodecs();
    var fory = ForySerializationProvider.get();
    fory.deserialize(fory.serialize(new PlayerMovementRequest(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0L)));
    fory.deserialize(fory.serialize(new PlayerMovementResponse(0L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0L, 0L, false)));
    fory.deserialize(fory.serialize(new ZoneStateDeltaMessage(0L, List.of(), List.of(), List.of())));
  }
}