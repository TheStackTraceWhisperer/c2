package com.example.zone.engine;

import com.example.config.ZoneServerProperties;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.entity.ZoneLineRecord;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import com.example.zone.spatial.FastMath3D;
import com.example.zone.spatial.SpatialGrid;
import com.example.zone.state.ZoneEntityState;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneSimulationEngine {

  public static final int TICK_RATE_HZ = 20;
  public static final long TICK_INTERVAL_MS = 1000 / TICK_RATE_HZ; // 50ms
  public static final float MAX_SPEED_UNITS_PER_SEC = 25.0f;
  public static final float MAX_DISTANCE_PER_TICK = (MAX_SPEED_UNITS_PER_SEC / TICK_RATE_HZ) * 2.0f; // 2.5m tolerance
  private static final long FLUSH_INTERVAL_TICKS = 600L; // 30s

  private final ZoneServerProperties properties;
  private final SpatialGrid spatialGrid;
  private final CharacterRepository characterRepository;
  private final ZoneLineJdbcRepository zoneLineRepository;
  private final VirtualThreadTaskDispatcher dispatcher;

  @Getter private final AtomicLong currentTick = new AtomicLong(0L);

  private final Map<Long, ZoneEntityState> entities = new ConcurrentHashMap<>();
  private final Map<Long, Long> sessionToCharacter = new ConcurrentHashMap<>();
  private final Queue<QueuedMovement> movementQueue = new ConcurrentLinkedQueue<>();
  private final List<ZoneLineRecord> cachedZoneLines = new CopyOnWriteArrayList<>();
  private ScheduledExecutorService tickExecutor;

  public record QueuedMovement(
    ChannelHandlerContext ctx,
    long sessionId,
    long characterId,
    PlayerMovementRequest request
  ) {}

  @PostConstruct
  public void start() {
    cachedZoneLines.addAll(zoneLineRepository.findBySourceZoneId(properties.getZoneId()));
    log.info("[ZONE-ENGINE] Loaded {} trigger zone lines for Zone #{}", cachedZoneLines.size(), properties.getZoneId());

    tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "zone-simulation-loop");
      t.setDaemon(true);
      return t;
    });

    tickExecutor.scheduleAtFixedRate(this::tick, 0, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    log.info("[ZONE-ENGINE] 20 Hz Authoritative Spatial Simulation initialized for Zone #{}", properties.getZoneId());
  }

  public int getAuthoritativeZoneId() {
    return properties.getZoneId();
  }

  public void registerSessionEntity(long sessionId, long characterId, ChannelHandlerContext ctx) {
    sessionToCharacter.put(sessionId, characterId);
    int zoneId = properties.getZoneId();

    entities.compute(characterId, (id, existing) -> {
      if (existing != null) {
        existing.setChannelContext(ctx);
        existing.setZoneId(zoneId);
        return existing;
      }

      Optional<CharacterRecord> recordOpt = characterRepository.findById(characterId);
      float initX = recordOpt.map(CharacterRecord::posX).orElse(0.0f);
      float initY = recordOpt.map(CharacterRecord::posY).orElse(0.0f);
      float initZ = recordOpt.map(CharacterRecord::posZ).orElse(0.0f);
      float initYaw = recordOpt.map(CharacterRecord::rotYaw).orElse(0.0f);
      float initPitch = recordOpt.map(CharacterRecord::rotPitch).orElse(0.0f);

      ZoneEntityState state = new ZoneEntityState(
        sessionId, characterId, zoneId,
        initX, initY, initZ,
        0.0f, 0.0f, 0.0f,
        initYaw, initPitch,
        0, currentTick.get(), ctx
      );

      recordOpt.ifPresent(r -> {
        state.setName(r.name());
        state.setMainJobId(r.mainJobId());
        state.setSubJobId(r.subJobId());
        state.setLevel((int) r.mainJobLevel());
      });

      spatialGrid.updatePosition(characterId, initX, initZ);
      return state;
    });
  }

  public void enqueueMovement(ChannelHandlerContext ctx, long sessionId, long characterId, PlayerMovementRequest req) {
    sessionToCharacter.putIfAbsent(sessionId, characterId);
    movementQueue.offer(new QueuedMovement(ctx, sessionId, characterId, req));
  }

  private void tick() {
    try {
      long tickNumber = currentTick.incrementAndGet();

      processInboundMovements(tickNumber);
      broadcastAoIDeltas(tickNumber);

      if (tickNumber % FLUSH_INTERVAL_TICKS == 0) {
        flushDirtyEntitiesAsync();
      }
    } catch (Throwable t) {
      log.error("[ZONE-ENGINE] Critical error in simulation tick", t);
    }
  }

  private void processInboundMovements(long tickNumber) {
    QueuedMovement m;
    int currentZoneId = properties.getZoneId();

    while ((m = movementQueue.poll()) != null) {
      PlayerMovementRequest req = m.request();
      long charId = m.characterId();
      long sid = m.sessionId();
      ChannelHandlerContext ctx = m.ctx();

      ZoneEntityState state = entities.get(charId);
      if (state == null) {
        registerSessionEntity(sid, charId, ctx);
        state = entities.get(charId);
      }

      state.setChannelContext(ctx);

      boolean zoneChanged = state.getZoneId() != currentZoneId;
      if (zoneChanged) {
        state.setZoneId(currentZoneId);
        state.setX(req.x());
        state.setY(req.y());
        state.setZ(req.z());
        state.getKnownEntities().clear();
      }

      float startX = state.getX();
      float startY = state.getY();
      float startZ = state.getZ();

      float dx = req.x() - startX;
      float dy = req.y() - startY;
      float dz = req.z() - startZ;
      double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

      boolean reconciled = false;
      if (!zoneChanged && dist > MAX_DISTANCE_PER_TICK && state.getLastUpdatedTick() > 0 && state.getLastUpdatedTick() != tickNumber) {
        log.warn("[ZONE-KINEMATICS] Client #{} exceeded max movement distance (dist={}/max={}). Reconciling.",
          charId, String.format("%.2f", dist), MAX_DISTANCE_PER_TICK);
        reconciled = true;
      } else {
        state.setX(req.x());
        state.setY(req.y());
        state.setZ(req.z());
      }

      state.setVx(req.vx());
      state.setVy(req.vy());
      state.setVz(req.vz());
      state.setYaw(req.yaw());
      state.setPitch(req.pitch());
      state.setMoveFlags(req.moveFlags());
      state.setLastUpdatedTick(tickNumber);
      state.setDirty(true);

      // Swept-OBB Collision Checks
      for (ZoneLineRecord line : cachedZoneLines) {
        if (FastMath3D.lineSegmentIntersectsObb(
          startX, startY, startZ,
          state.getX(), state.getY(), state.getZ(),
          line.centerX(), line.centerY(), line.centerZ(),
          line.extentX(), line.extentY(), line.extentZ(),
          line.rotationYaw())) {
          log.info("[ZONE-LINE] Player #{} triggered zone transition '{}' -> Target Zone #{}",
            charId, line.name(), line.targetZoneId());
          break;
        }
      }

      spatialGrid.updatePosition(charId, state.getX(), state.getZ());

      ctx.channel().attr(SessionContext.SESSION_ID).set(sid);
      ctx.channel().attr(SessionContext.CHARACTER_ID).set(charId);
      ctx.writeAndFlush(new PlayerMovementResponse(
        charId,
        state.getX(), state.getY(), state.getZ(),
        state.getVx(), state.getVy(), state.getVz(),
        state.getYaw(), state.getPitch(),
        state.getMoveFlags(),
        tickNumber,
        req.clientTick(),
        reconciled
      ));
    }
  }

  private void broadcastAoIDeltas(long tickNumber) {
    for (ZoneEntityState observer : entities.values()) {
      ChannelHandlerContext ctx = observer.getChannelContext();
      if (ctx == null || !ctx.channel().isActive()) {
        continue;
      }

      Set<Long> nearbyCharIds = spatialGrid.getNearbyEntities(observer.getX(), observer.getZ());
      Set<Long> known = observer.getKnownEntities();

      List<EntitySpawnRecord> spawns = new ArrayList<>();
      List<EntityMovementUpdate> updates = new ArrayList<>();
      List<Long> despawns = new ArrayList<>();

      for (Long neighborId : nearbyCharIds) {
        if (neighborId.equals(observer.getCharacterId())) {
          continue;
        }

        ZoneEntityState neighbor = entities.get(neighborId);
        if (neighbor == null) continue;

        if (!known.contains(neighborId)) {
          known.add(neighborId);
          spawns.add(new EntitySpawnRecord(
            neighbor.getCharacterId(),
            neighbor.getName(),
            neighbor.getMainJobId(),
            neighbor.getSubJobId(),
            neighbor.getLevel(),
            neighbor.getX(), neighbor.getY(), neighbor.getZ(),
            neighbor.getVx(), neighbor.getVy(), neighbor.getVz(),
            neighbor.getYaw(), neighbor.getPitch()
          ));
        } else if (neighbor.isDirty() || neighbor.getLastUpdatedTick() == tickNumber) {
          updates.add(new EntityMovementUpdate(
            neighbor.getCharacterId(),
            neighbor.getX(), neighbor.getY(), neighbor.getZ(),
            neighbor.getVx(), neighbor.getVy(), neighbor.getVz(),
            neighbor.getYaw(), neighbor.getPitch(),
            neighbor.getMoveFlags()
          ));
        }
      }

      Iterator<Long> knownIter = known.iterator();
      while (knownIter.hasNext()) {
        Long knownId = knownIter.next();
        if (!nearbyCharIds.contains(knownId) || !entities.containsKey(knownId)) {
          knownIter.remove();
          despawns.add(knownId);
        }
      }

      if (!spawns.isEmpty() || !updates.isEmpty() || !despawns.isEmpty()) {
        ctx.channel().attr(SessionContext.SESSION_ID).set(observer.getSessionId());
        ctx.channel().attr(SessionContext.CHARACTER_ID).set(observer.getCharacterId());
        ctx.writeAndFlush(new ZoneStateDeltaMessage(tickNumber, spawns, updates, despawns));
      }
    }
  }

  public void flushDirtyEntitiesAsync() {
    List<CharacterStateSnapshot> dirtySnapshots = new ArrayList<>();
    for (ZoneEntityState state : entities.values()) {
      if (state.isDirty()) {
        state.setDirty(false);
        dirtySnapshots.add(new CharacterStateSnapshot(
          state.getCharacterId(),
          state.getZoneId(),
          state.getX(), state.getY(), state.getZ(),
          state.getYaw(), state.getPitch()
        ));
      }
    }
    if (!dirtySnapshots.isEmpty()) {
      dispatcher.dispatch(() -> characterRepository.flushStateBatch(dirtySnapshots));
    }
  }

  public void removeEntityBySession(long sessionId) {
    Long characterId = sessionToCharacter.remove(sessionId);
    if (characterId != null) {
      ZoneEntityState state = entities.remove(characterId);
      spatialGrid.removeEntity(characterId);

      if (state != null && state.isDirty()) {
        CharacterStateSnapshot snapshot = new CharacterStateSnapshot(
          state.getCharacterId(),
          state.getZoneId(),
          state.getX(), state.getY(), state.getZ(),
          state.getYaw(), state.getPitch()
        );
        dispatcher.dispatch(() -> characterRepository.flushStateBatch(List.of(snapshot)));
      }
    }
  }

  @PreDestroy
  public void stop() {
    log.info("[ZONE-ENGINE] Stopping simulation loop and persisting remaining states...");
    if (tickExecutor != null) {
      tickExecutor.shutdown();
    }
    List<CharacterStateSnapshot> remaining = entities.values().stream()
      .map(s -> new CharacterStateSnapshot(s.getCharacterId(), s.getZoneId(), s.getX(), s.getY(), s.getZ(), s.getYaw(), s.getPitch()))
      .toList();
    characterRepository.flushStateBatch(remaining);

    sessionToCharacter.clear();
    entities.clear();
    spatialGrid.clear();
  }
}