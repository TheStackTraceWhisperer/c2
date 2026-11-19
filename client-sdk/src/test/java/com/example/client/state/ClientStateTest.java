package com.example.client.state;

import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.ZoneStateDeltaMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientStateTest {

  @Test
  @DisplayName("reset() clears interpolationBuffers alongside remoteEntities")
  void testResetClearsInterpolationBuffers() {
    ClientState state = new ClientState();
    state.addOrUpdateRemoteEntity(new EntitySpawnRecord(
      2001L, "Peer", (short) 1, (short) 0, 10, 1.0f, 2.0f, 3.0f, 0f, 0f, 0f, 0f, 0f
    ));

    assertFalse(state.getInterpolationBuffers().isEmpty());
    assertNotNull(state.sampleInterpolatedEntity(2001L, System.currentTimeMillis()));

    state.reset();

    assertTrue(state.getRemoteEntities().isEmpty());
    assertTrue(state.getInterpolationBuffers().isEmpty());
    assertNull(state.sampleInterpolatedEntity(2001L, System.currentTimeMillis()));
  }

  @Test
  @DisplayName("updateRemoteEntityMovement ignores updates for unknown/unspawned entities")
  void testUpdateRemoteEntityMovementIgnoresUnknownEntity() {
    ClientState state = new ClientState();
    AtomicInteger notifications = new AtomicInteger();
    state.onStateChange(s -> notifications.incrementAndGet());

    state.updateRemoteEntityMovement(new EntityMovementUpdate(
      9999L, 5.0f, 0.0f, 5.0f, 1.0f, 0f, 0f, 0f, 0f, 1
    ));

    assertTrue(state.getRemoteEntities().isEmpty());
    assertTrue(state.getInterpolationBuffers().isEmpty());
    assertEquals(0, notifications.get());
  }

  @Test
  @DisplayName("applyZoneStateDelta processes spawns, updates, despawns and notifies listeners once")
  void testApplyZoneStateDeltaNotifiesOnce() {
    ClientState state = new ClientState();
    state.addOrUpdateRemoteEntity(new EntitySpawnRecord(
      100L, "Existing", (short) 2, (short) 0, 5, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
    ));
    state.addOrUpdateRemoteEntity(new EntitySpawnRecord(
      101L, "Leaving", (short) 3, (short) 0, 5, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f
    ));

    AtomicInteger notifications = new AtomicInteger();
    state.onStateChange(s -> notifications.incrementAndGet());

    ZoneStateDeltaMessage delta = new ZoneStateDeltaMessage(
      42L,
      List.of(new EntitySpawnRecord(102L, "Newcomer", (short) 4, (short) 0, 10, 2f, 0f, 3f, 0f, 0f, 0f, 90f, 0f)),
      List.of(new EntityMovementUpdate(100L, 4f, 0f, 5f, 1f, 0f, 0f, 45f, 0f, 1)),
      List.of(101L)
    );

    state.applyZoneStateDelta(delta);

    assertEquals(1, notifications.get());
    assertTrue(state.getRemoteEntities().containsKey(100L));
    assertTrue(state.getRemoteEntities().containsKey(102L));
    assertFalse(state.getRemoteEntities().containsKey(101L));
    assertFalse(state.getInterpolationBuffers().containsKey(101L));
    assertEquals(4f, state.getRemoteEntities().get(100L).x());
  }
}
