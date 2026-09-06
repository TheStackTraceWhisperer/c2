package com.example.serialization;

import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
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
      1001L, "Hero", 1, (short) 1, (short) 1, 230, 10, (short) 1, (short) 1, (short) 0
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
      new SessionDisconnectedEvent(555L, System.currentTimeMillis()),

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

      // Zone (400 - 404)
      new PlayerMovementRequest(1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 10L),
      new PlayerMovementResponse(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 500L, 10L, true),
      new EntitySpawnRecord(1001L, "Hero", (short) 1, (short) 0, 10, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f),
      new EntityMovementUpdate(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1),
      new ZoneStateDeltaMessage(500L, List.of(), List.of(), List.of())
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
  @DisplayName("Security: BackendForySerializationProvider enforces strict class registration")
  void testStrictClassRegistrationEnforced() {
    ThreadSafeFory backendFory = BackendForySerializationProvider.get();

    record UnregisteredPayload(String secret) {}

    UnregisteredPayload unregistered = new UnregisteredPayload("not-registered");

    assertThatThrownBy(() -> backendFory.serialize(unregistered))
      .as("Serializing unregistered class in BackendForySerializationProvider must fail")
      .isInstanceOf(RuntimeException.class);
  }
}
