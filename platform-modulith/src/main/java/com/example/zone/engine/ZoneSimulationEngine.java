package com.example.zone.engine;

import com.example.asset.collision.CollisionMap;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.Vector3f;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.config.ZoneServerProperties;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.entity.NpcRecord;
import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.entity.ZoneLineRecord;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.NpcJdbcRepository;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.ZoneDatLoader;
import com.example.zone.collision.ZoneCollisionService;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import com.example.zone.patrol.NpcPatrolSimulation;
import com.example.zone.spatial.FastMath3D;
import com.example.zone.spatial.SpatialGrid;
import com.example.zone.state.ZoneEntityState;
import io.micronaut.core.annotation.Nullable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Singleton
public class ZoneSimulationEngine {

  public static final int TICK_RATE_HZ = 20;
  public static final long TICK_INTERVAL_MS = 1000 / TICK_RATE_HZ; // 50ms
  public static final float MAX_SPEED_UNITS_PER_SEC = 25.0f;
  public static final float MAX_DISTANCE_PER_TICK = (MAX_SPEED_UNITS_PER_SEC / TICK_RATE_HZ) * 2.0f; // 2.5m tolerance
  public static final int MAX_MOVEMENT_QUEUE_SIZE = 4096;
  public static final int MAX_SPAWNS_PER_DELTA = 30;
  public static final int MAX_UPDATES_PER_DELTA = 60;
  public static final int MAX_DESPAWNS_PER_DELTA = 100;
  private static final long FLUSH_INTERVAL_TICKS = 600L; // 30s
  private static final int MAX_DEFERRED_MOVEMENTS_PER_CHAR = 64;
  private static final int NPC_MOVING_NO_GROUND_SNAP_MASK = 0x8000;
  private static final float NPC_FLOOR_RAY_OFFSET_Y = 0.5f;
  private static final float NPC_FLOOR_RAY_MAX_DISTANCE = 5.0f;

  private final ZoneServerProperties properties;
  private final SpatialGrid spatialGrid;
  private final CharacterRepository characterRepository;
  private final ZoneLineJdbcRepository zoneLineRepository;
  private final VirtualThreadTaskDispatcher dispatcher;
  private final ZoneCollisionService collisionService;
  @Getter private final NpcPatrolSimulation patrolSimulation;
  @Nullable private final NpcJdbcRepository npcRepository;
  @Nullable private final ZoneInstanceJdbcRepository zoneInstanceRepository;

  @Getter private final AtomicLong currentTick = new AtomicLong(0L);

  private final Map<Long, ZoneEntityState> entities = new ConcurrentHashMap<>();
  private final Map<Long, Long> sessionToCharacter = new ConcurrentHashMap<>();
  private final Queue<QueuedMovement> movementQueue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger movementQueueSize = new AtomicInteger(0);
  private final ConcurrentLinkedQueue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
  private final Set<Long> pendingLoads = ConcurrentHashMap.newKeySet();
  private final Map<Long, List<QueuedMovement>> deferredMovements = new HashMap<>();
  private final List<QueuedMovement> readyDeferredMovements = new ArrayList<>();
  private final List<ZoneLineRecord> cachedZoneLines = new CopyOnWriteArrayList<>();
  private ScheduledExecutorService tickExecutor;

  @Inject
  public ZoneSimulationEngine(
      ZoneServerProperties properties,
      SpatialGrid spatialGrid,
      CharacterRepository characterRepository,
      ZoneLineJdbcRepository zoneLineRepository,
      VirtualThreadTaskDispatcher dispatcher,
      ZoneCollisionService collisionService,
      NpcPatrolSimulation patrolSimulation,
      @Nullable NpcJdbcRepository npcRepository,
      @Nullable ZoneInstanceJdbcRepository zoneInstanceRepository) {
    this.properties = properties;
    this.spatialGrid = spatialGrid;
    this.characterRepository = characterRepository;
    this.zoneLineRepository = zoneLineRepository;
    this.dispatcher = dispatcher;
    this.collisionService = collisionService;
    this.patrolSimulation = patrolSimulation;
    this.npcRepository = npcRepository;
    this.zoneInstanceRepository = zoneInstanceRepository;
  }

  public ZoneSimulationEngine(
      ZoneServerProperties properties,
      SpatialGrid spatialGrid,
      CharacterRepository characterRepository,
      ZoneLineJdbcRepository zoneLineRepository,
      VirtualThreadTaskDispatcher dispatcher,
      ZoneCollisionService collisionService,
      NpcPatrolSimulation patrolSimulation,
      @Nullable NpcJdbcRepository npcRepository) {
    this(properties, spatialGrid, characterRepository, zoneLineRepository, dispatcher, collisionService, patrolSimulation, npcRepository, null);
  }

  public ZoneSimulationEngine(
      ZoneServerProperties properties,
      SpatialGrid spatialGrid,
      CharacterRepository characterRepository,
      ZoneLineJdbcRepository zoneLineRepository,
      VirtualThreadTaskDispatcher dispatcher,
      ZoneCollisionService collisionService,
      NpcPatrolSimulation patrolSimulation) {
    this(properties, spatialGrid, characterRepository, zoneLineRepository, dispatcher, collisionService, patrolSimulation, null, null);
  }

  public record QueuedMovement(
    ChannelHandlerContext ctx,
    long sessionId,
    long characterId,
    PlayerMovementRequest request
  ) {}

  @PostConstruct
  public void start() {
    cachedZoneLines.addAll(zoneLineRepository.findBySourceZoneId(properties.getZoneId()));
    if (cachedZoneLines.isEmpty()) {
      loadDatZoneLines();
    }
    log.info("[ZONE-ENGINE] Loaded {} trigger zone lines for Zone #{}", cachedZoneLines.size(), properties.getZoneId());

    loadAuthenticNpcs();

    tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "zone-simulation-loop");
      t.setDaemon(true);
      return t;
    });

    tickExecutor.scheduleAtFixedRate(this::tick, 0, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    log.info("[ZONE-ENGINE] 20 Hz Authoritative Spatial Simulation initialized for Zone #{}", properties.getZoneId());
  }

  private void loadAuthenticNpcs() {
    if (npcRepository == null) {
      return;
    }
    List<NpcRecord> npcs = npcRepository.findByZoneId(properties.getZoneId());
    int loaded = 0;
    int skipped = 0;
    for (NpcRecord r : npcs) {
      if (!NpcSpawnFilter.shouldSpawn(r)) {
        skipped++;
        continue;
      }
      ZoneEntityState npcState = ZoneEntityState.createNpc(r);
      npcState.setY(resolveGroundedNpcY(r));
      registerNpc(npcState);
      loaded++;
    }
    log.info(
        "[ZONE-ENGINE] Spawned {} authentic LandSandBoat NPCs into Zone #{} (filtered/skipped {})",
        loaded,
        properties.getZoneId(),
        skipped);
  }

  /**
   * Resolves the authoritative spawn height for an NPC record in native FFXI coordinates ({@code -Y = Up}).
   * Grounded NPCs without {@code moving & 0x8000} snap downward onto the loaded zone collision floor when present.
   */
  float resolveGroundedNpcY(NpcRecord record) {
    if (record == null) {
      return 0.0f;
    }
    if (collisionService == null
        || !properties.isCollisionEnabled()
        || (record.moving() & NPC_MOVING_NO_GROUND_SNAP_MASK) != 0) {
      return record.posY();
    }
    Optional<CollisionMap> mapOpt = collisionService.getCollisionMap(record.zoneId());
    if (mapOpt.isEmpty()) {
      return record.posY();
    }
    Optional<RayHit> hit = mapOpt.get().raycastFloor(
        new Vector3f(record.posX(), record.posY(), record.posZ()),
        NPC_FLOOR_RAY_OFFSET_Y,
        NPC_FLOOR_RAY_MAX_DISTANCE,
        true);
    if (hit.isPresent() && Float.isFinite(hit.get().point().y())) {
      return hit.get().point().y();
    }
    return record.posY();
  }

  public int getAuthoritativeZoneId() {
    return properties.getZoneId();
  }

  public void registerSessionEntity(long sessionId, long characterId, ChannelHandlerContext ctx) {
    sessionToCharacter.put(sessionId, characterId);
    Optional<CharacterRecord> recordOpt = characterRepository.findById(characterId);
    commandQueue.offer(() -> applyEntityRegistration(sessionId, characterId, ctx, recordOpt));
  }

  private void applyEntityRegistration(
      long sessionId,
      long characterId,
      ChannelHandlerContext ctx,
      Optional<CharacterRecord> recordOpt) {
    sessionToCharacter.put(sessionId, characterId);
    int zoneId = properties.getZoneId();

    entities.compute(characterId, (id, existing) -> {
      if (existing != null) {
        existing.setChannelContext(ctx);
        existing.setZoneId(zoneId);
        return existing;
      }

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
        state.setWorldId(r.worldId());
        state.setName(r.name());
        state.setMainJobId(r.mainJobId());
        state.setSubJobId(r.subJobId());
        state.setLevel((int) r.mainJobLevel());
        state.setLookType("equipped");
        state.setRaceId(r.raceId());
        state.setFaceId(r.faceId());
        state.setSizeId(r.sizeId());
      });

      spatialGrid.updatePosition(characterId, initX, initZ);
      return state;
    });
  }

  public void enqueueMovement(ChannelHandlerContext ctx, long sessionId, long characterId, PlayerMovementRequest req) {
    int newSize = movementQueueSize.incrementAndGet();
    if (newSize > MAX_MOVEMENT_QUEUE_SIZE) {
      movementQueueSize.decrementAndGet();
      log.warn("[ZONE-ENGINE] Movement queue capacity ({}) exceeded; dropping movement from Session #{} (Char #{})",
        MAX_MOVEMENT_QUEUE_SIZE, sessionId, characterId);
      return;
    }
    sessionToCharacter.putIfAbsent(sessionId, characterId);
    movementQueue.offer(new QueuedMovement(ctx, sessionId, characterId, req));
  }

  void tick() {
    try {
      drainCommandQueue();
      long tickNumber = currentTick.incrementAndGet();

      processInboundMovements(tickNumber);
      patrolSimulation.tick(
          (float) TICK_INTERVAL_MS / 1000.0f,
          tickNumber,
          properties.getZoneId(),
          entities,
          spatialGrid,
          collisionService
      );
      broadcastAoIDeltas(tickNumber);

      if (tickNumber % FLUSH_INTERVAL_TICKS == 0) {
        flushDirtyEntitiesAsync();
      }
    } catch (Throwable t) {
      log.error("[ZONE-ENGINE] Critical error in simulation tick", t);
    }
  }

  private void drainCommandQueue() {
    Runnable cmd;
    while ((cmd = commandQueue.poll()) != null) {
      try {
        cmd.run();
      } catch (Throwable t) {
        log.error("[ZONE-ENGINE] Command queue task failed", t);
      }
    }
  }

  private void processInboundMovements(long tickNumber) {
    int currentZoneId = properties.getZoneId();

    if (!readyDeferredMovements.isEmpty()) {
      List<QueuedMovement> replay = new ArrayList<>(readyDeferredMovements);
      readyDeferredMovements.clear();
      for (QueuedMovement m : replay) {
        processSingleMovement(m, tickNumber, currentZoneId);
      }
    }

    QueuedMovement m;
    while ((m = movementQueue.poll()) != null) {
      movementQueueSize.decrementAndGet();
      processSingleMovement(m, tickNumber, currentZoneId);
    }
  }

  private void processSingleMovement(QueuedMovement m, long tickNumber, int currentZoneId) {
    PlayerMovementRequest req = m.request();
    long charId = m.characterId();
    long sid = m.sessionId();
    ChannelHandlerContext ctx = m.ctx();

    ZoneEntityState state = entities.get(charId);
    if (state == null) {
      if (!Long.valueOf(charId).equals(sessionToCharacter.get(sid))) {
        return;
      }
      List<QueuedMovement> pending = deferredMovements.computeIfAbsent(charId, k -> new ArrayList<>());
      if (pending.size() < MAX_DEFERRED_MOVEMENTS_PER_CHAR) {
        pending.add(m);
      } else {
        log.warn("[ZONE-ENGINE] Deferred movement queue full for Char #{}; dropping excess packet while loading", charId);
      }
      if (pendingLoads.add(charId)) {
        dispatchAsyncEntityLoad(sid, charId, ctx);
      }
      return;
    }

    state.setChannelContext(ctx);

    if (!isFinite(req)) {
      log.warn("[ZONE-KINEMATICS] Rejected non-finite movement values from Char #{}", charId);
      sendPlayerMovementResponse(ctx, sid, charId, state, tickNumber, req.clientTick(), true);
      return;
    }

    boolean zoneChanged = state.getZoneId() != currentZoneId;
    if (zoneChanged) {
      state.setZoneId(currentZoneId);
      state.setX(req.x());
      state.setY(req.y());
      state.setZ(req.z());
      state.getKnownEntities().clear();
      state.setDisplacementTick(tickNumber);
      state.setTickDisplacement(0.0);
    } else if (state.getDisplacementTick() != tickNumber) {
      state.setDisplacementTick(tickNumber);
      state.setTickDisplacement(0.0);
    }

    float startX = state.getX();
    float startY = state.getY();
    float startZ = state.getZ();

    // Clamp client velocity magnitude before delay-lead prediction
    float vx = req.vx();
    float vy = req.vy();
    float vz = req.vz();
    double speed = Math.sqrt((double) vx * vx + (double) vy * vy + (double) vz * vz);
    if (speed > MAX_SPEED_UNITS_PER_SEC) {
      double scale = MAX_SPEED_UNITS_PER_SEC / speed;
      vx = (float) (vx * scale);
      vy = (float) (vy * scale);
      vz = (float) (vz * scale);
    }

    float predX = req.x();
    float predY = req.y();
    float predZ = req.z();

    float delayLeadSec = (float) TICK_INTERVAL_MS / 1000.0f; // 50ms lead time (1 simulation tick)
    double clampedSpeedSq = (double) vx * vx + (double) vy * vy + (double) vz * vz;
    if (clampedSpeedSq > 1e-4) {
      predX += vx * delayLeadSec;
      predY += vy * delayLeadSec;
      predZ += vz * delayLeadSec;
    }

    double dx = (double) predX - startX;
    double dy = (double) predY - startY;
    double dz = (double) predZ - startZ;
    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

    boolean reconciled = false;
    boolean movementRejected = false;
    if (!zoneChanged && (state.getTickDisplacement() + dist) > MAX_DISTANCE_PER_TICK) {
      log.warn("[ZONE-KINEMATICS] Client #{} exceeded max movement distance (dist={}, tickTotal={}, max={}). Reconciling.",
        charId, String.format("%.2f", dist), String.format("%.2f", state.getTickDisplacement() + dist), MAX_DISTANCE_PER_TICK);
      reconciled = true;
      movementRejected = true;
    } else {
      ZoneCollisionService.MovementValidationResult val = collisionService.validateAndClamp(
        currentZoneId,
        startX, startY, startZ,
        predX, predY, predZ,
        req.moveFlags()
      );
      double actualDist = Math.sqrt(
        (double) (val.x() - startX) * (val.x() - startX)
          + (double) (val.y() - startY) * (val.y() - startY)
          + (double) (val.z() - startZ) * (val.z() - startZ)
      );
      state.setX(val.x());
      state.setY(val.y());
      state.setZ(val.z());
      state.setTickDisplacement(state.getTickDisplacement() + actualDist);
      if (val.reconciled()) {
        reconciled = true;
        log.debug("[ZONE-KINEMATICS] Client #{} movement reconciled: {}", charId, val.reason());
      }
    }

    if (!reconciled) {
      state.setVx(vx);
      state.setVy(vy);
      state.setVz(vz);
      state.setYaw(req.yaw());
      state.setPitch(req.pitch());
      state.setMoveFlags(req.moveFlags());
      state.setLastUpdatedTick(tickNumber);
      state.setDirty(true);
      state.setNetworkDirty(true);
    } else if (!movementRejected) {
      state.setYaw(req.yaw());
      state.setPitch(req.pitch());
      state.setMoveFlags(req.moveFlags());
      state.setLastUpdatedTick(tickNumber);
      state.setDirty(true);
      state.setNetworkDirty(true);
    } else {
      state.setLastUpdatedTick(tickNumber);
    }

    // Swept-OBB Collision Checks
    if (!movementRejected) {
      for (ZoneLineRecord line : cachedZoneLines) {
        if (FastMath3D.lineSegmentIntersectsObb(
          startX, startY, startZ,
          state.getX(), state.getY(), state.getZ(),
          line.centerX(), line.centerY(), line.centerZ(),
          line.extentX(), line.extentY(), line.extentZ(),
          line.rotationYaw())) {
          triggerZoneTransfer(ctx, sid, charId, state, line, startX, startY, startZ);
          return;
        }
      }
    }

    spatialGrid.updatePosition(charId, state.getX(), state.getZ());
    sendPlayerMovementResponse(ctx, sid, charId, state, tickNumber, req.clientTick(), reconciled);
  }

  private void dispatchAsyncEntityLoad(long sid, long charId, ChannelHandlerContext ctx) {
    boolean dispatched = false;
    try {
      dispatcher.dispatch(() -> {
        boolean queuedCompletion = false;
        try {
          Optional<CharacterRecord> recordOpt = characterRepository.findById(charId);
          commandQueue.offer(() -> {
            pendingLoads.remove(charId);
            List<QueuedMovement> pending = deferredMovements.remove(charId);
            if (!Long.valueOf(charId).equals(sessionToCharacter.get(sid))) {
              log.debug("[ZONE-ENGINE] Skipping async registration for disconnected Session #{} (Char #{})", sid, charId);
              return;
            }
            applyEntityRegistration(sid, charId, ctx, recordOpt);
            if (pending != null && !pending.isEmpty()) {
              readyDeferredMovements.addAll(pending);
            }
          });
          queuedCompletion = true;
        } catch (Exception e) {
          log.error("[ZONE-ENGINE] Failed async character load for Char #{}: {}", charId, e.getMessage(), e);
        } finally {
          if (!queuedCompletion) {
            commandQueue.offer(() -> {
              pendingLoads.remove(charId);
              deferredMovements.remove(charId);
            });
          }
        }
      });
      dispatched = true;
    } finally {
      if (!dispatched) {
        pendingLoads.remove(charId);
        deferredMovements.remove(charId);
      }
    }
  }

  private void triggerZoneTransfer(
      ChannelHandlerContext ctx,
      long sid,
      long charId,
      ZoneEntityState state,
      ZoneLineRecord line,
      float startX,
      float startY,
      float startZ) {
    log.info("[ZONE-LINE] Player #{} triggered zone transition '{}' -> Target Zone #{}",
      charId, line.name(), line.targetZoneId());

    final int targetZone = line.targetZoneId();
    final int worldId = state.getWorldId();
    final float destX = line.destX();
    final float destY = line.destY();
    final float destZ = line.destZ();
    final float rotYaw = state.getYaw();
    final float rotPitch = state.getPitch();
    final String fallbackHost = properties.getHost() != null ? properties.getHost() : "127.0.0.1";
    final int fallbackPort = properties.getPort();

    // Deregister from current zone simulation immediately so subsequent ticks do not re-trigger
    sessionToCharacter.remove(sid);
    deferredMovements.remove(charId);
    spatialGrid.removeEntity(charId);
    entities.remove(charId);

    // Sequence persistence BEFORE emitting gateway/client transfer messages
    dispatcher.dispatch(() -> {
      try {
        String targetHost = fallbackHost;
        int targetPort = fallbackPort;
        if (zoneInstanceRepository != null) {
          Optional<ZoneInstanceRecord> targetInstance =
            zoneInstanceRepository.findByWorldIdAndZoneIdAndIsStaticTrue(worldId, targetZone);
          if (targetInstance.isPresent()
              && targetInstance.get().host() != null
              && !targetInstance.get().host().isBlank()
              && targetInstance.get().port() > 0) {
            targetHost = targetInstance.get().host();
            targetPort = targetInstance.get().port();
          }
        }

        characterRepository.updatePosition(charId, targetZone, destX, destY, destZ, rotYaw, rotPitch);
        log.info("[ZONE-TRANSFER] Saved character #{} to target zone #{} at ({}, {}, {}) -> {}:{}",
          charId, targetZone, destX, destY, destZ, targetHost, targetPort);

        final String resolvedHost = targetHost;
        final int resolvedPort = targetPort;
        runOnChannelEventLoop(ctx, () -> {
          if (ctx.channel() != null) {
            ctx.channel().attr(SessionContext.SESSION_ID).set(sid);
            ctx.channel().attr(SessionContext.CHARACTER_ID).set(charId);
          }
          ctx.writeAndFlush(new SetSessionZoneControlMessage(targetZone, resolvedHost, resolvedPort));
          ctx.writeAndFlush(new ZoneTransferNotification(targetZone, resolvedHost, resolvedPort, destX, destY, destZ));
        });
      } catch (Exception e) {
        log.error("[ZONE-TRANSFER] Failed to persist zone transfer for character #{} to Zone #{}; aborting transfer: {}",
          charId, targetZone, e.getMessage(), e);
        commandQueue.offer(() -> {
          if (ctx != null && ctx.channel() != null && !ctx.channel().isActive()) {
            return;
          }
          state.setX(startX);
          state.setY(startY);
          state.setZ(startZ);
          state.setVx(0f);
          state.setVy(0f);
          state.setVz(0f);
          sessionToCharacter.put(sid, charId);
          entities.put(charId, state);
          spatialGrid.updatePosition(charId, startX, startZ);
        });
      }
    });
  }

  private void sendPlayerMovementResponse(
      ChannelHandlerContext ctx,
      long sid,
      long charId,
      ZoneEntityState state,
      long tickNumber,
      long clientTick,
      boolean reconciled) {
    PlayerMovementResponse response = new PlayerMovementResponse(
      charId,
      state.getX(), state.getY(), state.getZ(),
      state.getVx(), state.getVy(), state.getVz(),
      state.getYaw(), state.getPitch(),
      state.getMoveFlags(),
      tickNumber,
      clientTick,
      reconciled
    );
    runOnChannelEventLoop(ctx, () -> {
      if (ctx.channel() != null) {
        ctx.channel().attr(SessionContext.SESSION_ID).set(sid);
        ctx.channel().attr(SessionContext.CHARACTER_ID).set(charId);
      }
      ctx.writeAndFlush(response);
    });
  }

  private static boolean isFinite(PlayerMovementRequest req) {
    return Float.isFinite(req.x())
      && Float.isFinite(req.y())
      && Float.isFinite(req.z())
      && Float.isFinite(req.vx())
      && Float.isFinite(req.vy())
      && Float.isFinite(req.vz())
      && Float.isFinite(req.yaw())
      && Float.isFinite(req.pitch());
  }

  private void broadcastAoIDeltas(long tickNumber) {
    for (ZoneEntityState observer : entities.values()) {
      ChannelHandlerContext ctx = observer.getChannelContext();
      if (observer.isNpc() || ctx == null || ctx.channel() == null || !ctx.channel().isActive()) {
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
          spawns.add(new EntitySpawnRecord(
            neighbor.getCharacterId(),
            neighbor.getName(),
            neighbor.getMainJobId(),
            neighbor.getSubJobId(),
            neighbor.getLevel(),
            neighbor.getX(), neighbor.getY(), neighbor.getZ(),
            neighbor.getVx(), neighbor.getVy(), neighbor.getVz(),
            neighbor.getYaw(), neighbor.getPitch(),
            neighbor.toActorAppearance()
          ));
        } else if (neighbor.isNetworkDirty()) {
          updates.add(new EntityMovementUpdate(
            neighbor.getCharacterId(),
            neighbor.getX(), neighbor.getY(), neighbor.getZ(),
            neighbor.getVx(), neighbor.getVy(), neighbor.getVz(),
            neighbor.getYaw(), neighbor.getPitch(),
            neighbor.getMoveFlags()
          ));
        }
      }

      for (Long knownId : known) {
        if (!nearbyCharIds.contains(knownId) || !entities.containsKey(knownId)) {
          despawns.add(knownId);
        }
      }

      if (spawns.isEmpty() && updates.isEmpty() && despawns.isEmpty()) {
        continue;
      }

      int spawnIdx = 0;
      int updateIdx = 0;
      int despawnIdx = 0;
      while (spawnIdx < spawns.size() || updateIdx < updates.size() || despawnIdx < despawns.size()) {
        if (!ctx.channel().isActive()) {
          break;
        }
        int spawnEnd = Math.min(spawnIdx + MAX_SPAWNS_PER_DELTA, spawns.size());
        int updateEnd = Math.min(updateIdx + MAX_UPDATES_PER_DELTA, updates.size());
        int despawnEnd = Math.min(despawnIdx + MAX_DESPAWNS_PER_DELTA, despawns.size());

        List<EntitySpawnRecord> spawnChunk = new ArrayList<>(spawns.subList(spawnIdx, spawnEnd));
        List<EntityMovementUpdate> updateChunk = new ArrayList<>(updates.subList(updateIdx, updateEnd));
        List<Long> despawnChunk = new ArrayList<>(despawns.subList(despawnIdx, despawnEnd));

        for (EntitySpawnRecord spawn : spawnChunk) {
          known.add(spawn.characterId());
        }
        for (Long despawnId : despawnChunk) {
          known.remove(despawnId);
        }

        ZoneStateDeltaMessage chunkMessage = new ZoneStateDeltaMessage(tickNumber, spawnChunk, updateChunk, despawnChunk);
        runOnChannelEventLoop(ctx, () -> {
          if (ctx.channel().isActive()) {
            ctx.channel().attr(SessionContext.SESSION_ID).set(observer.getSessionId());
            ctx.channel().attr(SessionContext.CHARACTER_ID).set(observer.getCharacterId());
            ctx.writeAndFlush(chunkMessage);
          }
        });

        spawnIdx = spawnEnd;
        updateIdx = updateEnd;
        despawnIdx = despawnEnd;
      }
    }

    for (ZoneEntityState entity : entities.values()) {
      entity.setNetworkDirty(false);
    }
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

  public void registerNpc(ZoneEntityState npcState) {
    entities.put(npcState.getCharacterId(), npcState);
    spatialGrid.updatePosition(npcState.getCharacterId(), npcState.getX(), npcState.getZ());
  }

  public Map<Long, ZoneEntityState> getEntities() {
    return Collections.unmodifiableMap(entities);
  }

  public ZoneEntityState getEntity(long characterId) {
    return entities.get(characterId);
  }

  public int getEntityCount() {
    return entities.size();
  }

  public void flushDirtyEntitiesAsync() {
    List<CharacterStateSnapshot> dirtySnapshots = new ArrayList<>();
    for (ZoneEntityState state : entities.values()) {
      if (state.isNpc()) {
        continue;
      }
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
    commandQueue.offer(() -> {
      Long characterId = sessionToCharacter.remove(sessionId);
      if (characterId != null) {
        deferredMovements.remove(characterId);
        ZoneEntityState state = entities.remove(characterId);
        spatialGrid.removeEntity(characterId);

        if (state != null && state.isDirty() && !state.isNpc()) {
          CharacterStateSnapshot snapshot = new CharacterStateSnapshot(
            state.getCharacterId(),
            state.getZoneId(),
            state.getX(), state.getY(), state.getZ(),
            state.getYaw(), state.getPitch()
          );
          dispatcher.dispatch(() -> characterRepository.flushStateBatch(List.of(snapshot)));
        }
      }
    });
  }

  private void loadDatZoneLines() {
    byte[] zoneBytes = ZoneDatLoader.loadZoneDatBytes(properties.getZoneId(), properties);
    if (zoneBytes == null) {
      return;
    }
    List<ZoneInteraction> interactions = ZoneInteractionParser.parseInteractions(zoneBytes);
    int idx = 1;
    for (ZoneInteraction zi : interactions) {
      if (zi.isZoneLine()) {
        Integer resolvedTarget = zi.getTargetZoneId();
        int targetZoneId = (resolvedTarget != null) ? resolvedTarget : parseTargetZoneId(zi.getDestId(), properties.getZoneId());
        float halfX = Math.max(0.5f, Math.abs(zi.getSize().x()) / 2.0f);
        float halfY = Math.max(1.0f, Math.abs(zi.getSize().y()) / 2.0f);
        float halfZ = Math.max(0.5f, Math.abs(zi.getSize().z()) / 2.0f);
        float yaw = (float) Math.toDegrees(zi.getOrientation().y());

        cachedZoneLines.add(new ZoneLineRecord(
          idx++,
          properties.getZoneId(),
          targetZoneId,
          zi.getSourceId() + "->" + zi.getDestId(),
          "NORMAL",
          zi.getPosition().x(), zi.getPosition().y(), zi.getPosition().z(),
          halfX, halfY, halfZ,
          yaw,
          0f, 0f, 0f, 0f, 0f,
          0f, 0f, 0f,
          1000,
          0.5f
        ));
      }
    }
    log.info("[ZONE-ENGINE] Harvested {} authentic DAT zonelines for Zone #{}", cachedZoneLines.size(), properties.getZoneId());
  }

  private int parseTargetZoneId(String destId, int fallback) {
    if (destId == null) return fallback;
    String digits = destId.replaceAll("\\D+", "");
    if (!digits.isEmpty()) {
      try {
        return Integer.parseInt(digits);
      } catch (NumberFormatException ignored) {}
    }
    return fallback;
  }

  public List<ZoneLineRecord> getCachedZoneLines() {
    return Collections.unmodifiableList(cachedZoneLines);
  }

  @PreDestroy
  public void stop() {
    log.info("[ZONE-ENGINE] Stopping simulation loop and persisting remaining states...");
    if (tickExecutor != null) {
      tickExecutor.shutdown();
      try {
        if (!tickExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
          log.warn("[ZONE-ENGINE] Simulation tick loop did not terminate within 2s; forcing shutdown");
          tickExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        tickExecutor.shutdownNow();
      }
    }
    List<CharacterStateSnapshot> remaining = entities.values().stream()
      .filter(s -> !s.isNpc())
      .map(s -> new CharacterStateSnapshot(s.getCharacterId(), s.getZoneId(), s.getX(), s.getY(), s.getZ(), s.getYaw(), s.getPitch()))
      .toList();
    if (!remaining.isEmpty()) {
      characterRepository.flushStateBatch(remaining);
    }

    commandQueue.clear();
    movementQueue.clear();
    movementQueueSize.set(0);
    pendingLoads.clear();
    deferredMovements.clear();
    readyDeferredMovements.clear();
    sessionToCharacter.clear();
    entities.clear();
    spatialGrid.clear();
    patrolSimulation.clear();
  }
}
