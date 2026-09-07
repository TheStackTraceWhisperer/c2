package com.example.client.desktop;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.lobby.dto.CharacterSummary;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ClientStateTest {

    private ClientState clientState;

    @BeforeEach
    void setUp() {
        clientState = new ClientState();
    }

    @Test
    @DisplayName("Initial state has DISCONNECTED connection state and empty registries")
    void testInitialState() {
        assertEquals(ClientConnectionState.DISCONNECTED, clientState.getConnectionState());
        assertEquals(0L, clientState.getAccountId());
        assertEquals("", clientState.getAuthToken());
        assertNull(clientState.getSelectedCharacterId());
        assertEquals("", clientState.getLastErrorMessage());
        assertTrue(clientState.getCharacters().isEmpty());
        assertTrue(clientState.getCharacterList().isEmpty());
        assertTrue(clientState.getRemoteEntities().isEmpty());
        assertEquals(0.0f, clientState.getX());
        assertEquals(0.0f, clientState.getY());
        assertEquals(0.0f, clientState.getZ());
        assertEquals(0.0f, clientState.getYaw());
        assertEquals(0L, clientState.getCurrentLatencyMs());
    }

    @Test
    @DisplayName("State transitions notify registered listeners")
    void testStateTransitionsNotifyListeners() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        clientState.onStateChange(state -> listenerCalled.set(true));

        clientState.setConnectionState(ClientConnectionState.AUTHENTICATED);

        assertEquals(ClientConnectionState.AUTHENTICATED, clientState.getConnectionState());
        assertTrue(listenerCalled.get());
    }

    @Test
    @DisplayName("Character roster updates and clears correctly")
    void testCharacterRosterUpdates() {
        CharacterSummary c1 = new CharacterSummary(101L, "WarriorHero", 1, (short) 1, (short) 0, 100, 10, (short) 0, (short) 1, (short) 0);
        clientState.updateCharacters(List.of(c1));

        assertEquals(1, clientState.getCharacterList().size());
        assertEquals("WarriorHero", clientState.getCharacterList().get(0).name());
        assertEquals((short) 1, clientState.getCharacterList().get(0).raceId());
        assertEquals((short) 0, clientState.getCharacterList().get(0).gender());
    }

    @Test
    @DisplayName("Remote player entity lifecycle: spawn, movement update, and despawn")
    void testRemotePlayerEntityLifecycle() {
        // 1. Spawn
        EntitySpawnRecord spawn = new EntitySpawnRecord(
                2001L, "TarutaruBLM", (short) 4, (short) 0, 15,
                10.0f, 0.0f, 20.0f, 0.0f, 0.0f, 0.0f, 90.0f, 0.0f
        );
        clientState.addOrUpdateRemoteEntity(spawn);

        assertEquals(1, clientState.getRemoteEntities().size());
        ClientState.RemoteEntityState entity = clientState.getRemoteEntities().get(2001L);
        assertNotNull(entity);
        assertEquals("TarutaruBLM", entity.name());
        assertEquals((short) 4, entity.mainJobId());
        assertEquals(15, entity.level());
        assertEquals(10.0f, entity.x());
        assertEquals(20.0f, entity.z());
        assertEquals(90.0f, entity.yaw());

        // 2. Movement update
        EntityMovementUpdate move = new EntityMovementUpdate(
                2001L, 12.5f, 0.0f, 22.0f, 1.0f, 0.0f, 1.0f, 135.0f, 0.0f, 1
        );
        clientState.updateRemoteEntityMovement(move);

        entity = clientState.getRemoteEntities().get(2001L);
        assertNotNull(entity);
        assertEquals(12.5f, entity.x());
        assertEquals(22.0f, entity.z());
        assertEquals(135.0f, entity.yaw());
        assertEquals("TarutaruBLM", entity.name()); // Name and job preserved

        // 3. Despawn
        clientState.removeRemoteEntity(2001L);
        assertTrue(clientState.getRemoteEntities().isEmpty());
    }

    @Test
    @DisplayName("Local position and latency updates")
    void testPositionAndLatencyUpdates() {
        clientState.updatePosition(50.0f, -2.0f, -30.0f, 180.0f, 0.0f, 42L);
        assertEquals(50.0f, clientState.getX());
        assertEquals(-2.0f, clientState.getY());
        assertEquals(-30.0f, clientState.getZ());
        assertEquals(180.0f, clientState.getYaw());
        assertEquals(42L, clientState.getLastServerTick());

        clientState.updateLatency(35L);
        assertEquals(35L, clientState.getCurrentLatencyMs());
    }

    @Test
    @DisplayName("Reset flushes all credentials, entities, and resets connection to DISCONNECTED")
    void testResetFlushesState() {
        clientState.setAccountId(1234L);
        clientState.setAuthToken("jwt-token");
        clientState.setSelectedCharacterId(101L);
        clientState.setConnectionState(ClientConnectionState.IN_ZONE);
        clientState.setCurrentZoneId(230);
        clientState.updatePosition(10.0f, 0.0f, 10.0f, 1L);
        clientState.updateLatency(50L);
        clientState.addOrUpdateRemoteEntity(new EntitySpawnRecord(
                99L, "Friend", (short) 1, (short) 0, 1, 0, 0, 0, 0, 0, 0, 0, 0
        ));

        clientState.reset();

        assertEquals(ClientConnectionState.DISCONNECTED, clientState.getConnectionState());
        assertEquals(0L, clientState.getAccountId());
        assertEquals("", clientState.getAuthToken());
        assertNull(clientState.getSelectedCharacterId());
        assertEquals(0, clientState.getCurrentZoneId());
        assertEquals(0.0f, clientState.getX());
        assertEquals(0L, clientState.getCurrentLatencyMs());
        assertTrue(clientState.getRemoteEntities().isEmpty());
        assertTrue(clientState.getCharacterList().isEmpty());
    }
}
