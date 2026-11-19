package com.example.world.registry;

import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.protocol.EndpointTarget;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneRegistry {

  public record ZoneKey(int worldId, int zoneId) {}

  private final ZoneInstanceJdbcRepository instanceRepository;
  private final Map<ZoneKey, EndpointTarget> activeZones = new ConcurrentHashMap<>();

  public void refreshFromDatabase(int worldId) {
    activeZones.keySet().removeIf(key -> key.worldId() == worldId);
    for (ZoneInstanceRecord record : instanceRepository.findByWorldId(worldId)) {
      if (record.isStatic() && record.host() != null && !record.host().isBlank() && record.port() > 0) {
        registerZone(worldId, record.zoneId(), record.host(), record.port());
      }
    }
  }

  public void registerZone(int worldId, int zoneId, String host, int port) {
    log.info("[ZONE-REGISTRY] Registered Static Zone #{} (World #{}) at {}:{}", zoneId, worldId, host, port);
    activeZones.put(new ZoneKey(worldId, zoneId), new EndpointTarget(host, port));
  }

  public void unregisterZone(int worldId, int zoneId) {
    log.info("[ZONE-REGISTRY] Unregistered Zone #{} (World #{})", zoneId, worldId);
    activeZones.remove(new ZoneKey(worldId, zoneId));
  }

  public void evictZone(int worldId, int zoneId) {
    activeZones.remove(new ZoneKey(worldId, zoneId));
  }

  public EndpointTarget refreshZoneEndpoint(int worldId, int zoneId) {
    evictZone(worldId, zoneId);
    return getZoneEndpoint(worldId, zoneId);
  }

  public EndpointTarget getZoneEndpoint(int worldId, int zoneId) {
    ZoneKey key = new ZoneKey(worldId, zoneId);
    EndpointTarget cached = activeZones.get(key);
    if (cached != null) {
      return cached;
    }
    return instanceRepository.findByWorldIdAndZoneIdAndIsStaticTrue(worldId, zoneId)
      .filter(z -> z.host() != null && !z.host().isBlank() && z.port() > 0)
      .map(z -> {
        EndpointTarget target = new EndpointTarget(z.host(), z.port());
        activeZones.put(key, target);
        log.info("[ZONE-REGISTRY] Refreshed Static Zone #{} (World #{}) at {}:{}", zoneId, worldId, z.host(), z.port());
        return target;
      })
      .orElseGet(() -> {
        activeZones.remove(key);
        return null;
      });
  }

  public void clear() {
    activeZones.clear();
  }
}
