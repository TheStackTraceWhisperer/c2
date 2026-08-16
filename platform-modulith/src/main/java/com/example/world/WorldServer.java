package com.example.world;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.DemoProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.world.handler.JoinWorldRequestHandler;
import com.example.world.handler.ZoneTransferRequestHandler;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = {"world", Environment.TEST})
@Requires(notEnv = {"gateway", "login", "lobby", "zone"})
public class WorldServer extends AbstractBackendServer {

  private final JoinWorldRequestHandler joinWorldHandler;
  private final ZoneTransferRequestHandler zoneTransferHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;

  public WorldServer(
    DemoProperties properties,
    IndexedPacketRouter router,
    JoinWorldRequestHandler joinWorldHandler,
    ZoneTransferRequestHandler zoneTransferHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    super("WORLD", properties.getWorld().getPort(), router);
    this.joinWorldHandler = joinWorldHandler;
    this.zoneTransferHandler = zoneTransferHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.JOIN_WORLD_REQUEST, joinWorldHandler);
    router.register(MessageType.ZONE_TRANSFER_REQUEST, zoneTransferHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);
    start();
  }
}