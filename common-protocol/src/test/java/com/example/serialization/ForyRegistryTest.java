package com.example.serialization;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.lobby.dto.*;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class ForyRegistryTest {

  @Test
  @DisplayName("Registry Collision: All MessageType constants must have unique wire IDs")
  void testNoMessageTypeIdCollisions() throws IllegalAccessException {
    Map<Short, String> idToNameMap = new HashMap<>();
    Field[] fields = MessageType.class.getDeclaredFields();

    for (Field field : fields) {
      if (Modifier.isPublic(field.getModifiers())
        && Modifier.isStatic(field.getModifiers())
        && Modifier.isFinal(field.getModifiers())
        && field.getType() == short.class) {

        short id = field.getShort(null);
        String name = field.getName();

        assertThat(idToNameMap)
          .as("Wire ID %d is duplicated between %s and %s", id, idToNameMap.get(id), name)
          .doesNotContainKey(id);

        idToNameMap.put(id, name);
      }
    }
  }

  @Test
  @DisplayName("Registry Boundaries: Wire IDs must adhere to domain range partitions")
  void testMessageTypeRangePartitions() throws IllegalAccessException {
    Field[] fields = MessageType.class.getDeclaredFields();
    for (Field field : fields) {
      if (Modifier.isPublic(field.getModifiers())
        && Modifier.isStatic(field.getModifiers())
        && Modifier.isFinal(field.getModifiers())
        && field.getType() == short.class) {

        short id = field.getShort(null);
        String name = field.getName();

        if (name.startsWith("CTRL_")) {
          assertThat(id).as("%s must be in Gateway Control range [1, 49]", name).isBetween((short) 1, (short) 49);
        } else if (name.equals("PING") || name.equals("PONG") || name.equals("GATEWAY_ERROR_RESPONSE") || name.equals("SESSION_DISCONNECTED")) {
          assertThat(id).as("%s must be in System Diagnostics range [50, 99]", name).isBetween((short) 50, (short) 99);
        } else if (name.startsWith("LOGIN_") || name.startsWith("RECONNECT_")) {
          assertThat(id).as("%s must be in Auth range [100, 199]", name).isBetween((short) 100, (short) 199);
        } else if (name.startsWith("CHARACTER_") || name.startsWith("SELECT_CHARACTER_") || name.startsWith("CREATE_CHARACTER_")) {
          assertThat(id).as("%s must be in Lobby range [200, 299]", name).isBetween((short) 200, (short) 299);
        } else if (name.startsWith("JOIN_WORLD_") || name.startsWith("ZONE_TRANSFER_")) {
          assertThat(id).as("%s must be in World range [300, 399]", name).isBetween((short) 300, (short) 399);
        } else if (name.startsWith("PLAYER_MOVEMENT_") || name.startsWith("ENTITY_") || name.startsWith("ZONE_STATE_DELTA_")) {
          assertThat(id).as("%s must be in Zone range [400, 499]", name).isBetween((short) 400, (short) 499);
        }
      }
    }
  }

  @Test
  @DisplayName("Round-Trip Serialization: All common DTOs serialize and deserialize accurately")
  void testCommonDtoRoundTripSerialization() {
    ThreadSafeFory fory = ForySerializationProvider.get();

    CharacterSummary summary = new CharacterSummary(
      1001L, "Hero", 1, (short) 1, (short) 1, 230, 10, (short) 1, (short) 1, (short) 0
    );

    List<Object> sampleMessages = List.of(
      new PingMessage(123456789L, "ping-payload"),
      new PongMessage(123456789L, "pong-payload"),
      new GatewayErrorResponse((short) 1001, (short) 2, (short) 200, "Unauthenticated request"),
      new LoginRequest("user1", "pass1"),
      new LoginResponse(true, 10L, "token-abc", "Welcome"),
      new ReconnectRequest("token-abc"),
      new ReconnectResponse(true, 10L, 100L, "reconnected"),
      new CharacterListRequest(10L, "token-abc"),
      new CharacterListResponse(10L, List.of(summary)),
      summary,
      new SelectCharacterRequest("token-abc", 1001L),
      new SelectCharacterResponse(true, 1001L, "ok"),
      new CreateCharacterRequest("token-abc", 1, "NewHero", (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 1),
      new CreateCharacterResponse(true, 1002L, "Character created"),
      new JoinWorldRequest("token-abc"),
      new JoinWorldResponse(true, 230, "zone-host", 9001, "welcome to zone"),
      new ZoneTransferNotification(231, "zone-host-2", 9002, 10.0f, 20.0f, 30.0f),
      new PlayerMovementRequest(1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 100L),
      new PlayerMovementResponse(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1, 500L, 100L, true),
      new EntitySpawnRecord(1001L, "Hero", (short) 1, (short) 0, 10, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f),
      new EntityMovementUpdate(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1),
      new ZoneStateDeltaMessage(500L, List.of(), List.of(), List.of())
    );

    for (Object original : sampleMessages) {
      byte[] bytes = fory.serialize(original);
      assertThat(bytes).as("Serialized bytes for %s should not be empty", original.getClass().getSimpleName()).isNotEmpty();

      Object deserialized = fory.deserialize(bytes);
      assertThat(deserialized).as("Deserialized object should equal original for %s", original.getClass().getSimpleName())
        .isEqualTo(original);

      if (original instanceof PacketMessage packet) {
        assertThat(packet.messageId()).as("PacketMessage %s messageId should be positive", original.getClass().getSimpleName()).isGreaterThan((short) 0);
      }
    }
  }
}
