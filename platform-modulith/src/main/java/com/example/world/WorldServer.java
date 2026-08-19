package com.example.world;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.DemoProperties;
import com.example.config.DemoProperties.EndpointProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.SessionContext;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.ZoneTransferNotification;
import com.example.world.handler.JoinWorldRequestHandler;
import com.example.world.registry.WorldSessionRegistry;
import com.example.world.registry.ZoneRegistry;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = {"world", Environment.TEST})
@Requires(notEnv = {"gateway", "login", "lobby", "zone"})
public class WorldServer extends AbstractBackendServer {

  private final DemoProperties properties;
  private final ZoneRegistry zoneRegistry;
  private final WorldSessionRegistry sessionRegistry;
  private final CharacterRepository characterRepository;
  private final JoinWorldRequestHandler joinWorldHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;

  public WorldServer(
    DemoProperties properties,
    ZoneRegistry zoneRegistry,
    WorldSessionRegistry sessionRegistry,
    CharacterRepository characterRepository,
    IndexedPacketRouter router,
    JoinWorldRequestHandler joinWorldHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    super("WORLD", properties.getWorld().getPort(), router);
    this.properties = properties;
    this.zoneRegistry = zoneRegistry;
    this.sessionRegistry = sessionRegistry;
    this.characterRepository = characterRepository;
    this.joinWorldHandler = joinWorldHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.JOIN_WORLD_REQUEST, joinWorldHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);

    int zonePort = properties.getZone().getPort();
    String zoneHost = properties.getZone().getHostname();
    zoneRegistry.registerZone(1, zoneHost, zonePort);
    zoneRegistry.registerZone(2, zoneHost, zonePort);

    start();
  }

  /**
   * Server-authoritative zone transfer initiation.
   */
  public void transferPlayer(long sessionId, long characterId, int targetZoneId) {
    WorldSessionRegistry.WorldPlayerSession session = sessionRegistry.getSession(sessionId);
    if (session == null) {
      session = sessionRegistry.getByCharacterId(characterId);
    }

    if (session == null || session.getContext() == null) {
      log.error("[WORLD-SERVER] Cannot transfer Player #{}: active session stream not found", characterId);
      return;
    }

    ChannelHandlerContext ctx = session.getContext();
    EndpointProperties targetZoneEndpoint = zoneRegistry.getZoneEndpoint(targetZoneId);
    if (targetZoneEndpoint == null) {
      log.error("[WORLD-SERVER] Target Zone #{} is offline", targetZoneId);
      return;
    }

    float spawnX = (targetZoneId == 2) ? 550.0f : 100.0f;
    float spawnY = (targetZoneId == 2) ? 70.0f : 64.0f;
    float spawnZ = (targetZoneId == 2) ? -990.0f : -250.5f;

    sessionRegistry.updateZone(session.getSessionId(), targetZoneId, targetZoneEndpoint);

    // 1. Authoritative DB update before dispatching network handoff
    characterRepository.updatePosition(characterId, targetZoneId, spawnX, spawnY, spawnZ, 0.0f, 0.0f);

    log.info("[WORLD-SERVER] Orchestrating Authoritative Handoff for Session #{} (Char #{}) -> Zone #{} ({}:{})",
      session.getSessionId(), characterId, targetZoneId, targetZoneEndpoint.getHostname(), targetZoneEndpoint.getPort());

    // 2. Instruct Gateway to switch session route affinity to target zone
    ctx.channel().attr(SessionContext.SESSION_ID).set(session.getSessionId());
    ctx.channel().attr(SessionContext.CHARACTER_ID).set(characterId);
    ctx.write(new SetSessionZoneControlMessage(
      targetZoneId,
      targetZoneEndpoint.getHostname(),
      targetZoneEndpoint.getPort()
    ));

    // 3. Notify downstream client of new zone coordinates
    ctx.writeAndFlush(new ZoneTransferNotification(
      targetZoneId,
      targetZoneEndpoint.getHostname(),
      targetZoneEndpoint.getPort(),
      spawnX, spawnY, spawnZ
    ));
  }
}