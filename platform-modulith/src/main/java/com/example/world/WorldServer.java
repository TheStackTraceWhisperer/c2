package com.example.world;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.MessageType;
import com.example.config.WorldServerProperties;
import com.example.server.AbstractBackendServer;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.ZoneLineRecord;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.server.protocol.EndpointTarget;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.world.dto.ZoneTransferNotification;
import com.example.world.handler.JoinWorldRequestHandler;
import com.example.world.registry.WorldSessionRegistry;
import com.example.world.registry.ZoneRegistry;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

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
  @Nullable
  private final ZoneLineJdbcRepository zoneLineRepository;

  @Inject
  public WorldServer(
    WorldServerProperties properties,
    ZoneRegistry zoneRegistry,
    WorldSessionRegistry sessionRegistry,
    CharacterRepository characterRepository,
    VirtualThreadTaskDispatcher dispatcher,
    IndexedPacketRouter router,
    JoinWorldRequestHandler joinWorldHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler,
    @Nullable ZoneLineJdbcRepository zoneLineRepository) {
    super("WORLD", properties.getHost(), properties.getPort(), router);
    this.realmId = properties.getRealmId();
    this.zoneRegistry = zoneRegistry;
    this.sessionRegistry = sessionRegistry;
    this.characterRepository = characterRepository;
    this.dispatcher = dispatcher;
    this.joinWorldHandler = joinWorldHandler;
    this.sessionDisconnectedHandler = sessionDisconnectedHandler;
    this.zoneLineRepository = zoneLineRepository;
  }

  public WorldServer(
    WorldServerProperties properties,
    ZoneRegistry zoneRegistry,
    WorldSessionRegistry sessionRegistry,
    CharacterRepository characterRepository,
    VirtualThreadTaskDispatcher dispatcher,
    IndexedPacketRouter router,
    JoinWorldRequestHandler joinWorldHandler,
    SessionDisconnectedHandler sessionDisconnectedHandler) {
    this(properties, zoneRegistry, sessionRegistry, characterRepository, dispatcher, router, joinWorldHandler, sessionDisconnectedHandler, null);
  }

  @PostConstruct
  public void init() throws Exception {
    registerHandlers();

    log.info("[WORLD-SERVER] Booting World Coordinator for Realm #{}. Loading zone topology from database...", realmId);
    zoneRegistry.refreshFromDatabase(realmId);

    start();
  }

  void registerHandlers() {
    router.register(MessageType.JOIN_WORLD_REQUEST, joinWorldHandler);
    router.register(MessageType.SESSION_DISCONNECTED, sessionDisconnectedHandler);
    sessionDisconnectedHandler.onDisconnect(e -> {
      log.info("[WORLD-SERVER] Evicting session #{} from WorldSessionRegistry", e.sessionId());
      sessionRegistry.removeSession(e.sessionId());
    });
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

    final long resolvedSessionId = session.getSessionId();
    final int sourceZoneId = session.getCurrentZoneId();
    final ChannelHandlerContext ctx = session.getContext();

    dispatcher.dispatch(() -> {
      EndpointTarget targetZoneEndpoint = zoneRegistry.getZoneEndpoint(realmId, targetZoneId);
      if (targetZoneEndpoint == null) {
        log.error("[WORLD-SERVER] Target Zone #{} is offline for Realm #{}", targetZoneId, realmId);
        return;
      }

      SpawnTarget spawn = resolveSpawnCoordinates(characterId, sourceZoneId, targetZoneId);

      try {
        characterRepository.updatePosition(
          characterId,
          targetZoneId,
          spawn.x(),
          spawn.y(),
          spawn.z(),
          spawn.yaw(),
          spawn.pitch()
        );
      } catch (Exception e) {
        log.error("[WORLD-SERVER] Failed to persist zone transfer for Char #{} -> Zone #{}: {}",
          characterId, targetZoneId, e.getMessage(), e);
        return;
      }

      sessionRegistry.updateZone(resolvedSessionId, targetZoneId, targetZoneEndpoint);

      log.info("[WORLD-SERVER] Orchestrating Authoritative Handoff for Session #{} (Char #{}) -> Zone #{} ({}:{})",
        resolvedSessionId, characterId, targetZoneId, targetZoneEndpoint.host(), targetZoneEndpoint.port());

      runOnChannelEventLoop(ctx, () -> {
        if (ctx.channel() != null) {
          ctx.channel().attr(SessionContext.SESSION_ID).set(resolvedSessionId);
          ctx.channel().attr(SessionContext.CHARACTER_ID).set(characterId);
        }
        ctx.writeAndFlush(new SetSessionZoneControlMessage(
          targetZoneId,
          targetZoneEndpoint.host(),
          targetZoneEndpoint.port()
        ));

        ctx.writeAndFlush(new ZoneTransferNotification(
          targetZoneId,
          targetZoneEndpoint.host(),
          targetZoneEndpoint.port(),
          spawn.x(), spawn.y(), spawn.z()
        ));
      });
    });
  }

  private SpawnTarget resolveSpawnCoordinates(long characterId, int sourceZoneId, int targetZoneId) {
    if (zoneLineRepository != null) {
      Optional<ZoneLineRecord> matchingLine = zoneLineRepository.findBySourceZoneId(sourceZoneId).stream()
        .filter(line -> line.targetZoneId() == targetZoneId)
        .findFirst();
      if (matchingLine.isPresent()) {
        ZoneLineRecord line = matchingLine.get();
        return new SpawnTarget(line.destX(), line.destY(), line.destZ(), line.destYaw(), line.destPitch());
      }
    }

    Optional<CharacterRecord> charOpt = characterRepository.findById(characterId);
    if (charOpt.isPresent()) {
      CharacterRecord c = charOpt.get();
      if (c.zoneId() == targetZoneId) {
        return new SpawnTarget(c.posX(), c.posY(), c.posZ(), c.rotYaw(), c.rotPitch());
      }
      if (c.hpZoneId() == targetZoneId) {
        return new SpawnTarget(c.hpPosX(), c.hpPosY(), c.hpPosZ(), 0.0f, 0.0f);
      }
    }

    return new SpawnTarget(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
  }

  private void runOnChannelEventLoop(ChannelHandlerContext ctx, Runnable task) {
    if (ctx == null) {
      return;
    }
    EventExecutor executor = ctx.executor();
    if (executor == null || executor.inEventLoop()) {
      task.run();
    } else {
      executor.execute(task);
    }
  }

  private record SpawnTarget(float x, float y, float z, float yaw, float pitch) {}
}
