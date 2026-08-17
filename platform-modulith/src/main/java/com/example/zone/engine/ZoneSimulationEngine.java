package com.example.zone.engine;

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

  private final SpatialGrid spatialGrid;
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
    log.info("[ZONE-ENGINE] 20 Hz simulation loop running (interval: {}ms).", TICK_INTERVAL_MS);
  }

  public void registerSessionEntity(long sessionId, long characterId) {
    sessionToCharacter.put(sessionId, characterId);
  }

  public long resolveCharacterId(long sessionId) {
    // Default to mapped character ID, or standard entity range derived from session
    return sessionToCharacter.getOrDefault(sessionId, 1000L + (sessionId % 1000L));
  }

  public void enqueueMovement(ChannelHandlerContext ctx, long sessionId, long characterId, PlayerMovementRequest request) {
    sessionToCharacter.putIfAbsent(sessionId, characterId);
    movementQueue.offer(new QueuedMovement(ctx, sessionId, characterId, request));
  }

  private void tick() {
    try {
      long tickNumber = currentTick.incrementAndGet();

      QueuedMovement movement;
      while ((movement = movementQueue.poll()) != null) {
        processEntityMovement(tickNumber, movement);
      }
    } catch (Throwable t) {
      log.error("[ZONE-ENGINE] Unhandled exception in simulation tick", t);
    }
  }

  private void processEntityMovement(long tickNumber, QueuedMovement m) {
    PlayerMovementRequest req = m.request();
    entities.compute(m.characterId(), (k, v) -> {
      if (v == null) {
        return new ZoneEntityState(m.sessionId(), m.characterId(), req.x(), req.y(), req.z(), req.yaw(), req.pitch(), tickNumber);
      }
      v.setX(req.x());
      v.setY(req.y());
      v.setZ(req.z());
      v.setYaw(req.yaw());
      v.setPitch(req.pitch());
      v.setLastUpdatedTick(tickNumber);
      return v;
    });

    spatialGrid.updatePosition(m.characterId(), req.x(), req.z());
  }

  public void removeEntityBySession(long sessionId) {
    Long characterId = sessionToCharacter.remove(sessionId);
    if (characterId != null) {
      entities.remove(characterId);
      spatialGrid.removeEntity(characterId);
    }
  }

  @PreDestroy
  public void stop() {
    log.info("[ZONE-ENGINE] Stopping simulation loop...");
    if (tickExecutor != null) {
      tickExecutor.shutdown();
    }
    sessionToCharacter.clear();
    entities.clear();
    spatialGrid.clear();
  }
}