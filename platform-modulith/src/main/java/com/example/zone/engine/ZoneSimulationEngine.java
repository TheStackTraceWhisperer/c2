package com.example.zone.engine;

import com.example.config.DemoProperties;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.repository.CharacterRepository;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.spatial.SpatialGrid;
import com.example.zone.state.ZoneEntityState;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneSimulationEngine {

  private static final int TICK_RATE_HZ = 20;
  private static final long TICK_INTERVAL_MS = 1000 / TICK_RATE_HZ; // 50ms
  private static final long FLUSH_INTERVAL_TICKS = 600L; // Every 30 seconds (600 ticks @ 20Hz)

  private final DemoProperties properties;
  private final SpatialGrid spatialGrid;
  private final CharacterRepository characterRepository;
  private final VirtualThreadTaskDispatcher dispatcher;

  @Getter private final AtomicLong currentTick = new AtomicLong(0L);

  private final Map<Long, ZoneEntityState> entities = new ConcurrentHashMap<>();
  private final Map<Long, Long> sessionToCharacter = new ConcurrentHashMap<>();
  private final Queue<QueuedMovement> movementQueue = new ConcurrentLinkedQueue<>();
  private ScheduledExecutorService tickExecutor;

  public record QueuedMovement(
    ChannelHandlerContext ctx,
    long sessionId,
    long characterId,
    PlayerMovementRequest request
  ) {}

  @PostConstruct
  public void start() {
    tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "zone-simulation-loop");
      t.setDaemon(true);
      return t;
    });

    tickExecutor.scheduleAtFixedRate(this::tick, 0, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    log.info("[ZONE-ENGINE] 20 Hz simulation loop running for Zone #{} (interval: {}ms).",
      getAuthoritativeZoneId(), TICK_INTERVAL_MS);
  }

  public int getAuthoritativeZoneId() {
    return (properties.getZone() != null) ? properties.getZone().getZoneId() : 1;
  }

  public void registerSessionEntity(long sessionId, long characterId) {
    sessionToCharacter.put(sessionId, characterId);
  }

  public void enqueueMovement(ChannelHandlerContext ctx, long sessionId, long characterId, PlayerMovementRequest request) {
    sessionToCharacter.putIfAbsent(sessionId, characterId);

    long tickNumber = currentTick.get();
    int zoneId = getAuthoritativeZoneId();

    entities.compute(characterId, (k, v) -> {
      if (v == null) {
        return new ZoneEntityState(sessionId, characterId, zoneId, request.x(), request.y(), request.z(), request.yaw(), request.pitch(), tickNumber, true);
      }
      v.setZoneId(zoneId);
      v.setX(request.x());
      v.setY(request.y());
      v.setZ(request.z());
      v.setYaw(request.yaw());
      v.setPitch(request.pitch());
      v.setLastUpdatedTick(tickNumber);
      v.setDirty(true);
      return v;
    });

    movementQueue.offer(new QueuedMovement(ctx, sessionId, characterId, request));
  }

  private void tick() {
    try {
      long tickNumber = currentTick.incrementAndGet();

      QueuedMovement movement;
      while ((movement = movementQueue.poll()) != null) {
        spatialGrid.updatePosition(movement.characterId(), movement.request().x(), movement.request().z());
      }

      if (tickNumber % FLUSH_INTERVAL_TICKS == 0) {
        flushDirtyEntitiesAsync();
      }
    } catch (Throwable t) {
      log.error("[ZONE-ENGINE] Unhandled exception in simulation tick", t);
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
          state.getX(),
          state.getY(),
          state.getZ(),
          state.getYaw(),
          state.getPitch()
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

      if (state != null) {
        CharacterStateSnapshot snapshot = new CharacterStateSnapshot(
          state.getCharacterId(),
          state.getZoneId(),
          state.getX(),
          state.getY(),
          state.getZ(),
          state.getYaw(),
          state.getPitch()
        );
        dispatcher.dispatch(() -> characterRepository.flushStateBatch(List.of(snapshot)));
      }
    }
  }

  @PreDestroy
  public void stop() {
    log.info("[ZONE-ENGINE] Stopping simulation loop and flushing remaining states...");
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