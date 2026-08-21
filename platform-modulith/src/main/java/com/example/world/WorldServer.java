package com.example.world;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.WorldServerProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.server.protocol.EndpointTarget;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.world.dto.ZoneTransferNotification;
import com.example.world.handler.JoinWorldRequestHandler;
import com.example.world.registry.WorldSessionRegistry;
import com.example.world.registry.ZoneRegistry;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(env = "world")
public class WorldServer extends AbstractBackendServer {

  @Getter
  @Value("${app.world.realm-id:1}")
  private int realmId;

  private final ZoneRegistry zoneRegistry;
  private final WorldSessionRegistry sessionRegistry;
  private final CharacterRepository characterRepository;
  private final VirtualThreadTaskDispatcher dispatcher;
  private final JoinWorldRequestHandler joinWorldHandler;
  private final SessionDisconnectedHandler sessionDisconnectedHandler;

  public WorldServer(
    WorldServerProperties properties,
    ZoneRegistry zoneRegistry,
    WorldSessionRegistry sessionRegistry,
    CharacterRepository characterRepository,
    VirtualThreadTaskDispatcher dispatcher,
    IndexedPacketRouter router,
    JoinWorldRequestHandler joinWorldHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    super("WORLD", properties.getHost(), properties.getPort(), router);
    this.zoneRegistry = zoneRegistry;
    this.sessionRegistry = sessionRegistry;
    this.characterRepository = characterRepository;
    this.dispatcher = dispatcher;
    this.joinWorldHandler = joinWorldHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
  }

  @PostConstruct
  public void init() throws Exception {
    router.register(MessageType.JOIN_WORLD_REQUEST, joinWorldHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);

    log.info("[WORLD-SERVER] Booting World Coordinator for Realm #{}. Loading zone topology from database...", realmId);
    zoneRegistry.refreshFromDatabase(realmId);

    start();
  }

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
    EndpointTarget targetZoneEndpoint = zoneRegistry.getZoneEndpoint(realmId, targetZoneId);
    if (targetZoneEndpoint == null) {
      log.error("[WORLD-SERVER] Target Zone #{} is offline", targetZoneId);
      return;
    }

    float spawnX = (targetZoneId == 2) ? 550.0f : 100.0f;
    float spawnY = (targetZoneId == 2) ? 70.0f : 64.0f;
    float spawnZ = (targetZoneId == 2) ? -990.0f : -250.5f;

    sessionRegistry.updateZone(session.getSessionId(), targetZoneId, targetZoneEndpoint);

    dispatcher.dispatch(() -> {
      characterRepository.updatePosition(characterId, targetZoneId, spawnX, spawnY, spawnZ, 0.0f, 0.0f);
    });

    log.info("[WORLD-SERVER] Orchestrating Authoritative Handoff for Session #{} (Char #{}) -> Zone #{} ({}:{})",
      session.getSessionId(), characterId, targetZoneId, targetZoneEndpoint.host(), targetZoneEndpoint.port());

    ctx.channel().attr(SessionContext.SESSION_ID).set(session.getSessionId());
    ctx.channel().attr(SessionContext.CHARACTER_ID).set(characterId);
    ctx.write(new SetSessionZoneControlMessage(
      targetZoneId,
      targetZoneEndpoint.host(),
      targetZoneEndpoint.port()
    ));

    ctx.writeAndFlush(new ZoneTransferNotification(
      targetZoneId,
      targetZoneEndpoint.host(),
      targetZoneEndpoint.port(),
      spawnX, spawnY, spawnZ
    ));
  }
}