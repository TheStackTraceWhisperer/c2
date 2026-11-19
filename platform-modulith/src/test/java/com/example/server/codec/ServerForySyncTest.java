package com.example.server.codec;

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
import com.example.server.protocol.*;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.dto.*;
import org.apache.fory.ThreadSafeFory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerForySyncTest {

  @Test
  @DisplayName("ForySerializationProvider and BackendForySerializationProvider cross-serialize all common DTOs")
  void testCrossSerializationAndIsolation() {
    ThreadSafeFory clientFory = ForySerializationProvider.get();
    ThreadSafeFory backendFory = BackendForySerializationProvider.get();

    CharacterSummary summary = new CharacterSummary(
      1001L, "Hero", 1, (short) 1, (short) 1, 230, 10, (short) 1, (short) 1, (short) 0
    );
    EntitySpawnRecord spawn = new EntitySpawnRecord(
      1001L, "Hero", (short) 1, (short) 0, 10, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f
    );
    EntityMovementUpdate move = new EntityMovementUpdate(
      1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1
    );

    List<Object> dtos = List.of(
      new PingMessage(1L, "p"),
      new PongMessage(1L, "q"),
      new GatewayErrorResponse((short) 1001, (short) 1, (short) 100, "err"),
      new LoginRequest("u", "p"),
      new LoginResponse(true, 1L, "t", ""),
      new ReconnectRequest("t"),
      new ReconnectResponse(true, 1L, 2L, ""),
      new CharacterListRequest(1L, "t"),
      new CharacterListResponse(1L, List.of(summary)),
      summary,
      new SelectCharacterRequest("t", 1L),
      new SelectCharacterResponse(true, 1L, ""),
      new CreateCharacterRequest("t", 1, "Hero", (short) 1, (short) 0, (short) 1, (short) 1, (short) 1, (short) 0, (short) 1),
      new CreateCharacterResponse(true, 1L, ""),
      new JoinWorldRequest("t"),
      new JoinWorldResponse(true, 230, "h", 9000, ""),
      new ZoneTransferNotification(231, "h", 9001, 1f, 2f, 3f),
      new PlayerMovementRequest(1f, 2f, 3f, 0f, 0f, 0f, 0f, 0f, 1, 1L),
      new PlayerMovementResponse(1L, 1f, 2f, 3f, 0f, 0f, 0f, 0f, 0f, 1, 2L, 1L, false),
      spawn,
      move,
      new ZoneStateDeltaMessage(2L, List.of(spawn), List.of(move), List.of(3L))
    );

    for (Object dto : dtos) {
      assertThat(backendFory.deserialize(clientFory.serialize(dto))).isEqualTo(dto);
      assertThat(clientFory.deserialize(backendFory.serialize(dto))).isEqualTo(dto);
    }

    SessionDisconnectedEvent internalEvent = new SessionDisconnectedEvent(1L, 2L, true);
    assertThatThrownBy(() -> clientFory.serialize(internalEvent)).isInstanceOf(RuntimeException.class);

    byte[] internalBytes = backendFory.serialize(internalEvent);
    Object decoded = clientFory.deserialize(internalBytes);
    assertThat(decoded)
      .isNotInstanceOf(SessionDisconnectedEvent.class)
      .isNotInstanceOf(PacketMessage.class);
  }
}
