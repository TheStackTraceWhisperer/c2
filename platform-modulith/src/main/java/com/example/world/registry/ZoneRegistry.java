package com.example.world.registry;

import com.example.config.DemoProperties.EndpointProperties;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
public class ZoneRegistry {

  private final Map<Integer, EndpointProperties> activeZones = new ConcurrentHashMap<>();

  public void registerZone(int zoneId, String host, int port) {
    log.info("[ZONE-REGISTRY] Registered Zone #{} at {}:{}", zoneId, host, port);
    activeZones.put(zoneId, new EndpointProperties(port, host));
  }

  public void unregisterZone(int zoneId) {
    log.info("[ZONE-REGISTRY] Unregistered Zone #{}", zoneId);
    activeZones.remove(zoneId);
  }

  public EndpointProperties getZoneEndpoint(int zoneId) {
    return activeZones.get(zoneId);
  }

  public void clear() {
    activeZones.clear();
  }
}