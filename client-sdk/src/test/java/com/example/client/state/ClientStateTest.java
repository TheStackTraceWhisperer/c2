package com.example.client.state;

import com.example.common.protocol.RaceId;
import com.example.lobby.dto.CharacterSummary;
import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.ActorLookType;
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

  @Test
  @DisplayName("RemoteEntityState stores appearance on spawn and preserves it across EntityMovementUpdate")
  void testAppearanceStoredOnSpawnAndPreservedOnMovement() {
    ClientState state = new ClientState();
    ActorAppearance cerauleLook = ActorAppearance.fromRawNpcLook(
      "equipped", 3, 2,
      4108, 8204, 12300, 16396, 20492, 24576, 28672, 0,
      0, 1, 27, 32
    );

    state.addOrUpdateRemoteEntity(new EntitySpawnRecord(
      17719297L, "Ceraule", (short) 0, (short) 0, 1,
      -86f, 2f, -34f, 0f, 0f, 0f, 115f, 0f,
      cerauleLook
    ));

    ClientState.RemoteEntityState spawned = state.getRemoteEntities().get(17719297L);
    assertNotNull(spawned);
    assertEquals(cerauleLook, spawned.appearance());
    assertEquals(ActorLookType.EQUIPPED, spawned.appearance().lookType());
    assertEquals(12, spawned.appearance().head());

    state.updateRemoteEntityMovement(new EntityMovementUpdate(
      17719297L, -85f, 2f, -33f, 1f, 0f, 1f, 120f, 0f, 1
    ));

    ClientState.RemoteEntityState moved = state.getRemoteEntities().get(17719297L);
    assertNotNull(moved);
    assertEquals(-85f, moved.x());
    assertEquals(cerauleLook, moved.appearance());

    // Backwards-compatible 14-arg RemoteEntityState defaults appearance to NONE
    ClientState.RemoteEntityState legacy = new ClientState.RemoteEntityState(
      500L, "Legacy", (short) 1, (short) 0, 1,
      0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0
    );
    assertEquals(ActorAppearance.NONE, legacy.appearance());
  }

  @Test
  @DisplayName("getSelectedCharacterAppearance resolves race/face/size or defaults to Hume Male")
  void testGetSelectedCharacterAppearance() {
    ClientState state = new ClientState();

    // Default when no character is selected
    assertEquals(ActorAppearance.forPlayer(RaceId.HUME_MALE, 0, 1), state.getSelectedCharacterAppearance());

    CharacterSummary elvaan = new CharacterSummary(
      2001L, "Aurelie", 1, RaceId.ELVAAN_FEMALE, (short) 1, 230, 15,
      (short) 0, (short) 1, (short) 0,
      (short) 6, (short) 1, (short) 2
    );
    CharacterSummary taru = new CharacterSummary(
      2002L, "Shantotto", 1, RaceId.TARUTARU_FEMALE, (short) 1, 238, 75,
      (short) 2, (short) 4, (short) 0,
      (short) 3, (short) 0, (short) 0
    );
    state.updateCharacters(List.of(elvaan, taru));

    state.setSelectedCharacterId(2001L);
    ActorAppearance selected = state.getSelectedCharacterAppearance();
    assertEquals(ActorLookType.EQUIPPED, selected.lookType());
    assertEquals(RaceId.ELVAAN_FEMALE, selected.raceId());
    assertEquals(6, selected.faceId());
    assertEquals(2, selected.size());

    // Unknown selectedCharacterId falls back to default Hume Male
    state.setSelectedCharacterId(9999L);
    assertEquals(ActorAppearance.forPlayer(RaceId.HUME_MALE, 0, 1), state.getSelectedCharacterAppearance());
  }
}
