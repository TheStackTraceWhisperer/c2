package com.example.zone.engine;

import com.example.config.ZoneServerProperties;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.entity.ZoneLineRecord;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.zone.collision.ZoneCollisionService;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import com.example.zone.spatial.FastMath3D;
import com.example.zone.spatial.SpatialGrid;
import com.example.zone.state.ZoneEntityState;
import com.example.zone.patrol.NpcPatrolSimulation;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.asset.npc.NpcInfo;
import com.example.asset.npc.NpcTable;
import com.example.world.dto.ZoneTransferNotification;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private final ZoneCollisionService collisionService;
  @Getter private final NpcPatrolSimulation patrolSimulation;

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
    if (cachedZoneLines.isEmpty()) {
      loadDatZoneLines();
    }
    log.info("[ZONE-ENGINE] Loaded {} trigger zone lines for Zone #{}", cachedZoneLines.size(), properties.getZoneId());

    loadTownNpcs();

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

      // Transmission delay compensation: predict position forward along velocity
      float vx = req.vx();
      float vy = req.vy();
      float vz = req.vz();
      float vSq = vx * vx + vy * vy + vz * vz;

      float predX = req.x();
      float predY = req.y();
      float predZ = req.z();

      float delayLeadSec = (float) TICK_INTERVAL_MS / 1000.0f; // 50ms lead time (1 simulation tick)
      if (vSq > 1e-4f) {
        predX += vx * delayLeadSec;
        predY += vy * delayLeadSec;
        predZ += vz * delayLeadSec;
      }

      float dx = predX - startX;
      float dy = predY - startY;
      float dz = predZ - startZ;
      double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

      boolean reconciled = false;
      if (!zoneChanged && dist > MAX_DISTANCE_PER_TICK && state.getLastUpdatedTick() != tickNumber) {
        log.warn("[ZONE-KINEMATICS] Client #{} exceeded max movement distance (dist={}/max={}). Reconciling.",
          charId, String.format("%.2f", dist), MAX_DISTANCE_PER_TICK);
        reconciled = true;
      } else {
        ZoneCollisionService.MovementValidationResult val = collisionService.validateAndClamp(
          currentZoneId,
          startX, startY, startZ,
          predX, predY, predZ
        );
        state.setX(val.x());
        state.setY(val.y());
        state.setZ(val.z());
        if (val.reconciled()) {
          reconciled = true;
          log.debug("[ZONE-KINEMATICS] Client #{} movement reconciled: {}", charId, val.reason());
        }
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

          final int targetZone = line.targetZoneId();
          final float destX = line.destX();
          final float destY = line.destY();
          final float destZ = line.destZ();
          final float rotYaw = state.getYaw();
          final float rotPitch = state.getPitch();

          // 1. Authoritative persistence in target zone & spawn coordinates
          dispatcher.dispatch(() -> {
            try {
              characterRepository.updatePosition(charId, targetZone, destX, destY, destZ, rotYaw, rotPitch);
              log.info("[ZONE-TRANSFER] Saved character #{} to target zone #{} at ({}, {}, {})",
                charId, targetZone, destX, destY, destZ);
            } catch (Exception e) {
              log.error("[ZONE-TRANSFER] Failed to persist zone transfer for character #{}: {}", charId, e.getMessage(), e);
            }
          });

          // 2. Deregister from current zone simulation (triggers despawn for remaining observers)
          spatialGrid.removeEntity(charId);
          entities.remove(charId);

          // 3. Emit ZoneTransferNotification to client
          ctx.writeAndFlush(new ZoneTransferNotification(
            targetZone,
            properties.getHost() != null ? properties.getHost() : "127.0.0.1",
            properties.getPort(),
            destX,
            destY,
            destZ
          ));
          return;
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

  public void loadTownNpcs() {
    int zoneId = properties.getZoneId();
    byte[] npcBytes = loadNpcTableBytes();
    if (npcBytes == null || npcBytes.length == 0) {
      log.warn("[ZONE-ENGINE] No NpcTable-V3.DAT found — skipping town NPC population for Zone #{}", zoneId);
      return;
    }

    try {
      NpcTable table = NpcTable.load(npcBytes);
      List<NpcInfo> npcs = table.getByZone(zoneId);
      int count = 0;
      for (NpcInfo npc : npcs) {
        long entityId = npc.id();
        float yawDeg = (float) Math.toDegrees(npc.rotationRadians());
        String name = String.format("NPC-%d", npc.id());
        ZoneEntityState npcState = ZoneEntityState.createNpc(
          entityId,
          zoneId,
          name,
          npc.position().x(),
          npc.position().y(),
          npc.position().z(),
          yawDeg
        );
        registerNpc(npcState);
        count++;
      }
      log.info("[ZONE-ENGINE] Loaded {} town NPCs for Zone #{} into spatial simulation grid", count, zoneId);
    } catch (Exception e) {
      log.error("[ZONE-ENGINE] Failed to parse and load town NPCs for Zone #{}", zoneId, e);
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

  private byte[] loadNpcTableBytes() {
    String resPath = "/dats/tables/landsandboat/NpcTable-V3.DAT";
    try (InputStream stream = getClass().getResourceAsStream(resPath)) {
      if (stream != null) {
        return stream.readAllBytes();
      }
    } catch (Exception ignored) {}

    String configuredDir = properties.getAssetPath();
    List<Path> candidates = List.of(
      Path.of(configuredDir, "tables/landsandboat/NpcTable-V3.DAT"),
      Path.of(configuredDir, "NpcTable-V3.DAT"),
      Path.of("game-asset-pipeline/src/test/resources/dats/tables/landsandboat/NpcTable-V3.DAT"),
      Path.of("../game-asset-pipeline/src/test/resources/dats/tables/landsandboat/NpcTable-V3.DAT"),
      Path.of("src/test/resources/dats/tables/landsandboat/NpcTable-V3.DAT")
    );

    for (Path p : candidates) {
      if (Files.isRegularFile(p)) {
        try {
          return Files.readAllBytes(p);
        } catch (Exception ignored) {}
      }
    }
    return null;
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
    Long characterId = sessionToCharacter.remove(sessionId);
    if (characterId != null) {
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
  }

  private void loadDatZoneLines() {
    byte[] zoneBytes = loadZoneDatBytes(properties.getZoneId());
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

  byte[] loadZoneDatBytes(int zoneId) {
    String resPath = "/dats/zones/south_san_doria_230.DAT";
    try (InputStream stream = getClass().getResourceAsStream(resPath)) {
      if (stream != null) {
        return stream.readAllBytes();
      }
    } catch (Exception ignored) {}

    String configuredDir = properties.getAssetPath();
    List<Path> candidates = List.of(
      Path.of(configuredDir, "zones/south_san_doria_230.DAT"),
      Path.of(configuredDir, "south_san_doria_230.DAT"),
      Path.of("game-asset-pipeline/src/test/resources/dats/zones/south_san_doria_230.DAT"),
      Path.of("../game-asset-pipeline/src/test/resources/dats/zones/south_san_doria_230.DAT"),
      Path.of("src/test/resources/dats/zones/south_san_doria_230.DAT")
    );

    for (Path p : candidates) {
      if (Files.isRegularFile(p)) {
        try {
          return Files.readAllBytes(p);
        } catch (Exception ignored) {}
      }
    }
    return null;
  }

  public List<ZoneLineRecord> getCachedZoneLines() {
    return Collections.unmodifiableList(cachedZoneLines);
  }

  @PreDestroy
  public void stop() {
    log.info("[ZONE-ENGINE] Stopping simulation loop and persisting remaining states...");
    if (tickExecutor != null) {
      tickExecutor.shutdown();
    }
    List<CharacterStateSnapshot> remaining = entities.values().stream()
      .filter(s -> !s.isNpc())
      .map(s -> new CharacterStateSnapshot(s.getCharacterId(), s.getZoneId(), s.getX(), s.getY(), s.getZ(), s.getYaw(), s.getPitch()))
      .toList();
    if (!remaining.isEmpty()) {
      characterRepository.flushStateBatch(remaining);
    }

    sessionToCharacter.clear();
    entities.clear();
    spatialGrid.clear();
    patrolSimulation.clear();
  }
}