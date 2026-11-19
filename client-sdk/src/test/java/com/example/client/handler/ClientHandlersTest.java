package com.example.client.handler;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.CreateCharacterResponse;
import com.example.login.dto.LoginResponse;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.ZoneStateDeltaMessage;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientHandlersTest {

  @Test
  @DisplayName("ZoneTransferNotificationHandler clears remoteEntities + interpolationBuffers without double notify")
  void testZoneTransferNotificationHandler() {
    ClientState state = new ClientState();
    state.setConnectionState(ClientConnectionState.IN_ZONE);
    state.addOrUpdateRemoteEntity(new EntitySpawnRecord(
      2001L, "NPC", (short) 99, (short) 0, 1, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f
    ));

    AtomicInteger notifications = new AtomicInteger();
    state.onStateChange(s -> notifications.incrementAndGet());

    ZoneTransferNotificationHandler handler = new ZoneTransferNotificationHandler(state);
    handler.handle(null, new ZoneTransferNotification(231, "127.0.0.1", 9002, 10f, 1f, -20f));

    assertEquals(1, notifications.get());
    assertEquals(231, state.getCurrentZoneId());
    assertEquals(10f, state.getX());
    assertEquals(1f, state.getY());
    assertEquals(-20f, state.getZ());
    assertTrue(state.getRemoteEntities().isEmpty());
    assertTrue(state.getInterpolationBuffers().isEmpty());
    assertEquals(ClientConnectionState.IN_ZONE, state.getConnectionState());
  }

  @Test
  @DisplayName("LoginResponseHandler stores lastErrorMessage on failure and notifies once per response")
  void testLoginResponseHandler() {
    ClientState state = new ClientState();
    AtomicInteger notifications = new AtomicInteger();
    state.onStateChange(s -> notifications.incrementAndGet());

    LoginResponseHandler handler = new LoginResponseHandler(state);
    handler.handle(null, new LoginResponse(false, 0L, "", "Invalid credentials"));

    assertEquals(1, notifications.get());
    assertEquals("Invalid credentials", state.getLastErrorMessage());
    assertEquals(ClientConnectionState.CONNECTED, state.getConnectionState());

    notifications.set(0);
    handler.handle(null, new LoginResponse(true, 42L, "jwt-123", ""));

    assertEquals(1, notifications.get());
    assertEquals(42L, state.getAccountId());
    assertEquals("jwt-123", state.getAuthToken());
    assertEquals("", state.getLastErrorMessage());
    assertEquals(ClientConnectionState.AUTHENTICATED, state.getConnectionState());
  }

  @Test
  @DisplayName("JoinWorldResponseHandler stores lastErrorMessage on failure and notifies once on success")
  void testJoinWorldResponseHandler() {
    ClientState state = new ClientState();
    state.addOrUpdateRemoteEntity(new EntitySpawnRecord(
      3001L, "Old", (short) 1, (short) 0, 1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
    ));
    AtomicInteger notifications = new AtomicInteger();
    state.onStateChange(s -> notifications.incrementAndGet());

    JoinWorldResponseHandler handler = new JoinWorldResponseHandler(state);
    handler.handle(null, new JoinWorldResponse(false, 0, "", 0, "Zone offline"));

    assertEquals(1, notifications.get());
    assertEquals("Zone offline", state.getLastErrorMessage());

    notifications.set(0);
    handler.handle(null, new JoinWorldResponse(true, 230, "127.0.0.1", 9001, ""));

    assertEquals(1, notifications.get());
    assertEquals(230, state.getCurrentZoneId());
    assertEquals(ClientConnectionState.IN_ZONE, state.getConnectionState());
    assertEquals("", state.getLastErrorMessage());
    assertTrue(state.getRemoteEntities().isEmpty());
    assertTrue(state.getInterpolationBuffers().isEmpty());
  }

  @Test
  @DisplayName("CreateCharacterResponseHandler stores error on failure and supports 0-arg constructor")
  void testCreateCharacterResponseHandler() {
    CreateCharacterResponseHandler noStateHandler = new CreateCharacterResponseHandler();
    assertDoesNotThrow(() -> noStateHandler.handle(null, new CreateCharacterResponse(false, 0L, "Name taken")));

    ClientState state = new ClientState();
    CreateCharacterResponseHandler handler = new CreateCharacterResponseHandler(state);
    handler.handle(null, new CreateCharacterResponse(false, 0L, "Invalid raceId"));
    assertEquals("Invalid raceId", state.getLastErrorMessage());

    handler.handle(null, new CreateCharacterResponse(true, 1005L, ""));
    assertEquals("", state.getLastErrorMessage());
  }

  @Test
  @DisplayName("ZoneStateDeltaHandler applies full delta with single notification")
  void testZoneStateDeltaHandler() {
    ClientState state = new ClientState();
    AtomicInteger notifications = new AtomicInteger();
    state.onStateChange(s -> notifications.incrementAndGet());

    ZoneStateDeltaHandler handler = new ZoneStateDeltaHandler(state);
    handler.handle(null, new ZoneStateDeltaMessage(
      1L,
      List.of(
        new EntitySpawnRecord(1L, "A", (short) 1, (short) 0, 1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        new EntitySpawnRecord(2L, "B", (short) 2, (short) 0, 2, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f)
      ),
      List.of(),
      List.of()
    ));

    assertEquals(1, notifications.get());
    assertEquals(2, state.getRemoteEntities().size());
  }

  @Test
  @DisplayName("PongPacketHandler has @Inject on ClientState constructor and records latency")
  void testPongPacketHandlerInjectAndLatency() throws NoSuchMethodException {
    Constructor<PongPacketHandler> ctor = PongPacketHandler.class.getConstructor(ClientState.class);
    assertNotNull(ctor.getAnnotation(Inject.class));

    ClientState state = new ClientState();
    PongPacketHandler handler = new PongPacketHandler(state);
    handler.handle(null, new PongMessage(System.currentTimeMillis() - 25L, "PONG"));
    assertTrue(state.getCurrentLatencyMs() >= 20L);
  }
}
