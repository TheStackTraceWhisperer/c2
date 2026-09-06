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

  private final ZoneInstanceJdbcRepository instanceRepository;
  private final Map<Integer, EndpointTarget> activeZones = new ConcurrentHashMap<>();

  public void refreshFromDatabase(int worldId) {
    for (ZoneInstanceRecord record : instanceRepository.findByWorldId(worldId)) {
      if (record.isStatic() && record.host() != null && !record.host().isBlank()) {
        registerZone(record.zoneId(), record.host(), record.port());
      }
    }
  }

  public void registerZone(int zoneId, String host, int port) {
    log.info("[ZONE-REGISTRY] Registered Static Zone #{} at {}:{}", zoneId, host, port);
    activeZones.put(zoneId, new EndpointTarget(host, port));
  }

  public void unregisterZone(int zoneId) {
    log.info("[ZONE-REGISTRY] Unregistered Zone #{}", zoneId);
    activeZones.remove(zoneId);
  }

  public EndpointTarget getZoneEndpoint(int worldId, int zoneId) {
    EndpointTarget cached = activeZones.get(zoneId);
    if (cached != null) {
      return cached;
    }
    return instanceRepository.findByWorldIdAndZoneIdAndIsStaticTrue(worldId, zoneId)
      .filter(z -> z.host() != null && !z.host().isBlank())
      .map(z -> {
        registerZone(z.zoneId(), z.host(), z.port());
        return new EndpointTarget(z.host(), z.port());
      })
      .orElse(null);
  }

  public void clear() {
    activeZones.clear();
  }
}