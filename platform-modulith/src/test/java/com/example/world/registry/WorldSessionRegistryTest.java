package com.example.world.registry;

import com.example.server.protocol.EndpointTarget;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSessionRegistryTest {

  @Test
  @DisplayName("Registers, updates, and safely evicts sessions without clobbering newer session bindings for the same character")
  void testSessionLifecycleAndDisconnectIsolation() {
    WorldSessionRegistry registry = new WorldSessionRegistry();
    EndpointTarget ep230 = new EndpointTarget("127.0.0.1", 9230);
    EndpointTarget ep231 = new EndpointTarget("127.0.0.1", 9231);

    registry.registerSession(100L, 1001L, 230, ep230, null);
    assertThat(registry.getSession(100L)).isNotNull();
    assertThat(registry.getByCharacterId(1001L).getSessionId()).isEqualTo(100L);

    registry.updateZone(100L, 231, ep231);
    assertThat(registry.getSession(100L).getCurrentZoneId()).isEqualTo(231);
    assertThat(registry.getSession(100L).getCurrentZoneEndpoint()).isEqualTo(ep231);

    // Character reconnects on new session 200L before stale session 100L disconnect fires
    registry.registerSession(200L, 1001L, 231, ep231, null);
    registry.removeSession(100L);

    assertThat(registry.getSession(100L)).isNull();
    assertThat(registry.getSession(200L)).isNotNull();
    assertThat(registry.getByCharacterId(1001L)).isNotNull();
    assertThat(registry.getByCharacterId(1001L).getSessionId()).isEqualTo(200L);

    registry.removeSession(200L);
    assertThat(registry.getByCharacterId(1001L)).isNull();
  }
}
