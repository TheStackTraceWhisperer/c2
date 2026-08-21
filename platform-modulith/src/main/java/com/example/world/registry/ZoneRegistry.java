package com.example.world.registry;

import com.example.server.entity.ZoneTopologyRecord;
import com.example.server.protocol.EndpointTarget;
import com.example.server.repository.ZoneTopologyJdbcRepository;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneRegistry {

  private final ZoneTopologyJdbcRepository topologyRepository;
  private final Map<Integer, EndpointTarget> activeZones = new ConcurrentHashMap<>();

  public void refreshFromDatabase(int realmId) {
    for (ZoneTopologyRecord record : topologyRepository.findByRealmId(realmId)) {
      if (record.host() != null && !record.host().isBlank()) {
        registerZone(record.id(), record.host(), record.port());
      }
    }
  }

  public void registerZone(int zoneId, String host, int port) {
    log.info("[ZONE-REGISTRY] Registered Zone #{} at {}:{}", zoneId, host, port);
    activeZones.put(zoneId, new EndpointTarget(host, port));
  }

  public void unregisterZone(int zoneId) {
    log.info("[ZONE-REGISTRY] Unregistered Zone #{}", zoneId);
    activeZones.remove(zoneId);
  }

  public EndpointTarget getZoneEndpoint(int realmId, int zoneId) {
    EndpointTarget cached = activeZones.get(zoneId);
    if (cached != null) {
      return cached;
    }
    // Query database directly for newly provisioned or updated Pod IPs
    return topologyRepository.findByIdAndRealmId(zoneId, realmId)
      .filter(z -> z.host() != null && !z.host().isBlank())
      .map(z -> {
        registerZone(z.id(), z.host(), z.port());
        return new EndpointTarget(z.host(), z.port());
      })
      .orElse(null);
  }

  public void clear() {
    activeZones.clear();
  }
}