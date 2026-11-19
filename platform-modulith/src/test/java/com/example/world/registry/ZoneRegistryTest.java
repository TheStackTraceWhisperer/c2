package com.example.world.registry;

import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.protocol.EndpointTarget;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneRegistryTest {

  @Test
  @DisplayName("Keys activeZones by ZoneKey(worldId, zoneId) and refreshes cleanly on eviction/miss")
  void testZoneKeyIsolationAndEvictionRefreshOnMiss() {
    Map<ZoneRegistry.ZoneKey, ZoneInstanceRecord> db = new HashMap<>();
    db.put(new ZoneRegistry.ZoneKey(1, 230),
      new ZoneInstanceRecord(1, 1, 230, "DEFAULT", "10.1.0.230", 9230, "ONLINE", true, Instant.now()));
    db.put(new ZoneRegistry.ZoneKey(2, 230),
      new ZoneInstanceRecord(2, 2, 230, "DEFAULT", "10.2.0.230", 9230, "ONLINE", true, Instant.now()));

    AtomicInteger dbLookups = new AtomicInteger(0);
    ZoneInstanceJdbcRepository repo = (ZoneInstanceJdbcRepository) Proxy.newProxyInstance(
      ZoneInstanceJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ZoneInstanceJdbcRepository.class},
      (proxy, method, args) -> {
        if ("findByWorldId".equals(method.getName())) {
          int w = (int) args[0];
          return db.entrySet().stream()
            .filter(e -> e.getKey().worldId() == w)
            .map(Map.Entry::getValue)
            .toList();
        }
        if ("findByWorldIdAndZoneIdAndIsStaticTrue".equals(method.getName())) {
          dbLookups.incrementAndGet();
          return Optional.ofNullable(db.get(new ZoneRegistry.ZoneKey((int) args[0], (int) args[1])));
        }
        return null;
      }
    );

    ZoneRegistry registry = new ZoneRegistry(repo);
    registry.refreshFromDatabase(1);
    registry.refreshFromDatabase(2);

    // Same zoneId 230 in World 1 vs World 2 must return distinct endpoints without collision
    EndpointTarget w1 = registry.getZoneEndpoint(1, 230);
    EndpointTarget w2 = registry.getZoneEndpoint(2, 230);
    assertThat(w1).isEqualTo(new EndpointTarget("10.1.0.230", 9230));
    assertThat(w2).isEqualTo(new EndpointTarget("10.2.0.230", 9230));
    assertThat(dbLookups.get()).isEqualTo(0);

    // Update DB endpoint and verify evictZone + cache-miss refresh fetches new endpoint
    db.put(new ZoneRegistry.ZoneKey(1, 230),
      new ZoneInstanceRecord(1, 1, 230, "DEFAULT", "10.1.0.99", 9999, "ONLINE", true, Instant.now()));
    assertThat(registry.getZoneEndpoint(1, 230)).isEqualTo(new EndpointTarget("10.1.0.230", 9230));

    registry.evictZone(1, 230);
    EndpointTarget refreshed = registry.getZoneEndpoint(1, 230);
    assertThat(refreshed).isEqualTo(new EndpointTarget("10.1.0.99", 9999));
    assertThat(dbLookups.get()).isEqualTo(1);

    // World 2 entry remains untouched
    assertThat(registry.getZoneEndpoint(2, 230)).isEqualTo(new EndpointTarget("10.2.0.230", 9230));
  }
}
