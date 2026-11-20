package com.example.serialization;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.PacketMessage;
import com.example.lobby.dto.*;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.*;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.ActorLookType;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import org.apache.fory.ThreadSafeFory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ForySyncIT {

  @Test
  @DisplayName("Zero-Drift: BackendForySerializationProvider round-trips all common DTOs and backend control messages")
  void testBackendSerializationSync() {
    ThreadSafeFory backendFory = BackendForySerializationProvider.get();

    CharacterSummary summary = new CharacterSummary(
      1001L, "Hero", 1, (short) 1, (short) 1, 230, 10, (short) 1, (short) 1, (short) 0,
      (short) 5, (short) 2, (short) 1
    );
    ActorAppearance equippedAppearance = ActorAppearance.fromRawNpcLook(
      "equipped", 3, 2,
      4108, 8204, 12300, 16396, 20492, 24576, 28672, 0,
      0, 1, 27, 32
    );
    ActorAppearance standardAppearance = ActorAppearance.forStandardModel(996, 1, 27, 32);
    ActorAppearance chocoboAppearance = ActorAppearance.fromRawNpcLook(
      "chocobo", 0, 34,
      0, 1, 0, 0, 0, 0, 0, 0,
      0, 0, 25, 32
    );
    EntitySpawnRecord populatedSpawn = new EntitySpawnRecord(
      1001L, "Hero", (short) 1, (short) 0, 10, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f,
      equippedAppearance
    );

    List<Object> allPayloads = List.of(
      // Backend Control Plane (1 - 4)
      new SetSessionAuthStateControlMessage(true, 100L),
      new SetSessionCharacterControlMessage(1001L),
      new SetSessionWorldControlMessage(1, "127.0.0.1", 9000),
      new SetSessionZoneControlMessage(230, "127.0.0.1", 9001),

      // System Diagnostics (50 - 52)
      new PingMessage(123456789L, "ping"),
      new PongMessage(123456789L, "pong"),
      new GatewayErrorResponse((short) 1001, (short) 2, (short) 200, "Unauthenticated"),

      // Backend-only event (99)
      new SessionDisconnectedEvent(555L, System.currentTimeMillis(), true),

      // Auth (100 - 103)
      new LoginRequest("admin", "secret"),
      new LoginResponse(true, 100L, "token-xyz", "ok"),
      new ReconnectRequest("token-xyz"),
      new ReconnectResponse(true, 100L, 555L, "reconnected"),

      // Lobby (200 - 206)
      new CharacterListRequest(100L, "token-xyz"),
      new CharacterListResponse(100L, List.of(summary)),
      summary,
      new SelectCharacterRequest("token-xyz", 1001L),
      new SelectCharacterResponse(true, 1001L, "selected"),
      new CreateCharacterRequest("token-xyz", 1, "NewHero", (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 1),
      new CreateCharacterResponse(true, 1002L, "created"),

      // World (300 - 302)
      new JoinWorldRequest("token-xyz"),
      new JoinWorldResponse(true, 230, "zone-host", 9001, "joined"),
      new ZoneTransferNotification(231, "zone-host-2", 9002, 1.0f, 2.0f, 3.0f),

      // Zone (400 - 406)
      new PlayerMovementRequest(1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 10L),
      new PlayerMovementResponse(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 500L, 10L, true),
      ActorLookType.NONE,
      ActorLookType.EQUIPPED,
      ActorLookType.STANDARD,
      ActorLookType.CHOCOBO,
      ActorLookType.AUTOMATON,
      equippedAppearance,
      standardAppearance,
      chocoboAppearance,
      populatedSpawn,
      new EntityMovementUpdate(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1),
      new ZoneStateDeltaMessage(
        500L,
        List.of(populatedSpawn),
        List.of(new EntityMovementUpdate(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1)),
        List.of(2002L)
      )
    );

    for (Object original : allPayloads) {
      byte[] bytes = backendFory.serialize(original);
      assertThat(bytes).as("Serialized bytes for %s should not be empty", original.getClass().getSimpleName()).isNotEmpty();

      Object deserialized = backendFory.deserialize(bytes);
      assertThat(deserialized).as("Deserialized object should match original for %s", original.getClass().getSimpleName())
        .isEqualTo(original);
    }
  }

  @Test
  @DisplayName("Cross-Provider Sync: ForySerializationProvider <-> BackendForySerializationProvider interoperate for every common DTO")
  void testCrossProviderSerializationSync() {
    ThreadSafeFory clientFory = ForySerializationProvider.get();
    ThreadSafeFory backendFory = BackendForySerializationProvider.get();

    CharacterSummary summary = new CharacterSummary(
      1001L, "Hero", 1, (short) 1, (short) 1, 230, 10, (short) 1, (short) 1, (short) 0,
      (short) 5, (short) 2, (short) 1
    );
    ActorAppearance equippedAppearance = ActorAppearance.fromRawNpcLook(
      "equipped", 3, 2,
      4108, 8204, 12300, 16396, 20492, 24576, 28672, 0,
      0, 1, 27, 32
    );
    ActorAppearance standardAppearance = ActorAppearance.forStandardModel(996, 1, 27, 32);
    ActorAppearance chocoboAppearance = ActorAppearance.fromRawNpcLook(
      "chocobo", 0, 34,
      0, 1, 0, 0, 0, 0, 0, 0,
      0, 0, 25, 32
    );
    EntitySpawnRecord spawn = new EntitySpawnRecord(
      1001L, "Hero", (short) 1, (short) 0, 10, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f,
      equippedAppearance
    );
    EntityMovementUpdate move = new EntityMovementUpdate(
      1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1
    );

    List<Object> commonDtos = List.of(
      new PingMessage(123456789L, "ping"),
      new PongMessage(123456789L, "pong"),
      new GatewayErrorResponse((short) 1001, (short) 2, (short) 200, "Unauthenticated"),
      new LoginRequest("admin", "secret"),
      new LoginResponse(true, 100L, "token-xyz", "ok"),
      new ReconnectRequest("token-xyz"),
      new ReconnectResponse(true, 100L, 555L, "reconnected"),
      new CharacterListRequest(100L, "token-xyz"),
      new CharacterListResponse(100L, List.of(summary)),
      summary,
      new SelectCharacterRequest("token-xyz", 1001L),
      new SelectCharacterResponse(true, 1001L, "selected"),
      new CreateCharacterRequest("token-xyz", 1, "NewHero", (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 1),
      new CreateCharacterResponse(true, 1002L, "created"),
      new JoinWorldRequest("token-xyz"),
      new JoinWorldResponse(true, 230, "zone-host", 9001, "joined"),
      new ZoneTransferNotification(231, "zone-host-2", 9002, 1.0f, 2.0f, 3.0f),
      new PlayerMovementRequest(1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 10L),
      new PlayerMovementResponse(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 500L, 10L, true),
      ActorLookType.NONE,
      ActorLookType.EQUIPPED,
      ActorLookType.STANDARD,
      ActorLookType.CHOCOBO,
      ActorLookType.AUTOMATON,
      equippedAppearance,
      standardAppearance,
      chocoboAppearance,
      spawn,
      move,
      new ZoneStateDeltaMessage(500L, List.of(spawn), List.of(move), List.of(2002L))
    );

    for (Object dto : commonDtos) {
      byte[] clientBytes = clientFory.serialize(dto);
      assertThat(backendFory.deserialize(clientBytes))
        .as("BackendFory deserializing ClientFory bytes for %s", dto.getClass().getSimpleName())
        .isEqualTo(dto);

      byte[] backendBytes = backendFory.serialize(dto);
      assertThat(clientFory.deserialize(backendBytes))
        .as("ClientFory deserializing BackendFory bytes for %s", dto.getClass().getSimpleName())
        .isEqualTo(dto);
    }
  }

  @Test
  @DisplayName("Security: ForySerializationProvider & BackendForySerializationProvider enforce strict isolation & registration")
  void testStrictClassRegistrationEnforced() {
    ThreadSafeFory backendFory = BackendForySerializationProvider.get();
    ThreadSafeFory clientFory = ForySerializationProvider.get();

    record UnregisteredPayload(String secret) {}

    UnregisteredPayload unregistered = new UnregisteredPayload("not-registered");

    assertThatThrownBy(() -> backendFory.serialize(unregistered))
      .as("Serializing unregistered class in BackendForySerializationProvider must fail")
      .isInstanceOf(RuntimeException.class);

    assertThatThrownBy(() -> clientFory.serialize(unregistered))
      .as("Serializing unregistered class in ForySerializationProvider must fail")
      .isInstanceOf(RuntimeException.class);

    SessionDisconnectedEvent disconnectEvent = new SessionDisconnectedEvent(1L, 100L, false);
    assertThatThrownBy(() -> clientFory.serialize(disconnectEvent))
      .as("Client Fory must reject serializing backend-only SessionDisconnectedEvent")
      .isInstanceOf(RuntimeException.class);

    byte[] forgedDisconnect = backendFory.serialize(disconnectEvent);
    Object decodedDisconnect = clientFory.deserialize(forgedDisconnect);
    assertThat(decodedDisconnect)
      .as("Client Fory must never instantiate SessionDisconnectedEvent or PacketMessage from internal type ID 99")
      .isNotInstanceOf(SessionDisconnectedEvent.class)
      .isNotInstanceOf(PacketMessage.class);

    SetSessionAuthStateControlMessage ctrlMsg = new SetSessionAuthStateControlMessage(true, 1L);
    assertThatThrownBy(() -> clientFory.serialize(ctrlMsg))
      .as("Client Fory must reject serializing backend-only SetSessionAuthStateControlMessage")
      .isInstanceOf(RuntimeException.class);

    byte[] forgedControl = backendFory.serialize(ctrlMsg);
    Object decodedControl = clientFory.deserialize(forgedControl);
    assertThat(decodedControl)
      .as("Client Fory must never instantiate SetSessionAuthStateControlMessage or PacketMessage from internal type ID 1")
      .isNotInstanceOf(SetSessionAuthStateControlMessage.class)
      .isNotInstanceOf(PacketMessage.class);
  }
}
