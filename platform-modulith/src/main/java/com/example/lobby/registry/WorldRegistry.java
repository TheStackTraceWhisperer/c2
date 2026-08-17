package com.example.lobby.registry;

import com.example.config.DemoProperties.EndpointProperties;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
public class WorldRegistry {

  private final Map<Integer, EndpointProperties> activeWorlds = new ConcurrentHashMap<>();

  public void registerWorld(int worldId, String host, int port) {
    log.info("[WORLD-REGISTRY] Registered World #{} at {}:{}", worldId, host, port);
    activeWorlds.put(worldId, new EndpointProperties(port, host));
  }

  public void unregisterWorld(int worldId) {
    log.info("[WORLD-REGISTRY] Unregistered World #{}", worldId);
    activeWorlds.remove(worldId);
  }

  public EndpointProperties getWorldEndpoint(int worldId) {
    return activeWorlds.get(worldId);
  }

  public void clear() {
    activeWorlds.clear();
  }
}