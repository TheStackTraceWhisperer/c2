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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
      1001L, "Hero", 1, (short) 1, (short) 1, 230, 10, (short) 1, (short) 1, (short) 0,
      (short) 4, (short) 2, (short) 2
    );
    ActorAppearance equippedAppearance = ActorAppearance.fromRawNpcLook(
      "equipped", 3, 2,
      4108, 8204, 12300, 16396, 20492, 24576, 28672, 32775,
      0, 1, 27, 32
    );
    ActorAppearance standardAppearance = ActorAppearance.forStandardModel(996, 1, 27, 32);
    ActorAppearance chocoboAppearance = ActorAppearance.fromRawNpcLook(
      "chocobo", 0, 34,
      0, 1, 0, 0, 0, 0, 0, 0,
      0, 0, 25, 32
    );
    EntitySpawnRecord spawnWithAppearance = new EntitySpawnRecord(
      1001L, "Ceraule", (short) 1, (short) 0, 10,
      1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f,
      equippedAppearance
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
      ActorLookType.NONE,
      ActorLookType.EQUIPPED,
      ActorLookType.STANDARD,
      ActorLookType.CHOCOBO,
      ActorLookType.AUTOMATON,
      equippedAppearance,
      standardAppearance,
      chocoboAppearance,
      spawnWithAppearance,
      new EntityMovementUpdate(1001L, 1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1),
      new ZoneStateDeltaMessage(
        500L,
        List.of(
          spawnWithAppearance,
          new EntitySpawnRecord(1002L, "Well", (short) 0, (short) 0, 1, 4.0f, 0.0f, 5.0f, 0f, 0f, 0f, 0f, 0f, standardAppearance),
          new EntitySpawnRecord(1003L, "Chocobo", (short) 0, (short) 0, 1, 6.0f, 0.0f, 7.0f, 0f, 0f, 0f, 0f, 0f, chocoboAppearance)
        ),
        List.of(new EntityMovementUpdate(1001L, 1.5f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f, 90.0f, 0.0f, 1)),
        List.of(1002L)
      )
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

  @Test
  @DisplayName("ActorAppearance.fromRawNpcLook strips 0x0FFF slot prefixes, resolves chocobo faceId->raceId, and handles all look types")
  void testActorAppearanceFromRawNpcLook() {
    // Equipped: Ceraule slot words with 4-bit prefixes (0x100C..0x7000)
    ActorAppearance equipped = ActorAppearance.fromRawNpcLook(
      " equipped ", 3, 2,
      4108, 8204, 12300, 16396, 20492, 24576, 28672, 32785,
      999, 1, 27, 32
    );
    assertThat(equipped.lookType()).isEqualTo(ActorLookType.EQUIPPED);
    assertThat(equipped.raceId()).isEqualTo(3);
    assertThat(equipped.faceId()).isEqualTo(2);
    assertThat(equipped.head()).isEqualTo(12);
    assertThat(equipped.body()).isEqualTo(12);
    assertThat(equipped.hands()).isEqualTo(12);
    assertThat(equipped.legs()).isEqualTo(12);
    assertThat(equipped.feet()).isEqualTo(12);
    assertThat(equipped.mainWeapon()).isEqualTo(0);
    assertThat(equipped.subShield()).isEqualTo(0);
    assertThat(equipped.ranged()).isEqualTo(17);
    assertThat(equipped.modelId()).isEqualTo(0);
    assertThat(equipped.size()).isEqualTo(1);
    assertThat(equipped.animationSub()).isEqualTo(1);
    assertThat(equipped.entityFlags()).isEqualTo(27);
    assertThat(equipped.namePrefix()).isEqualTo(32);

    // Chocobo: V2__npc_list.sql stores chocobo race (32..36) in faceId with raceId=0
    ActorAppearance chocoboFromFace = ActorAppearance.fromRawNpcLook(
      "CHOCOBO", 0, 34,
      0x1002, 0x2003, 0x3004, 0x4005, 0x5006, 0x6007, 0x7008, 0x8009,
      55, 2, 25, 32
    );
    assertThat(chocoboFromFace.lookType()).isEqualTo(ActorLookType.CHOCOBO);
    assertThat(chocoboFromFace.raceId()).isEqualTo(34);
    assertThat(chocoboFromFace.faceId()).isEqualTo(0);
    assertThat(chocoboFromFace.head()).isEqualTo(2);
    assertThat(chocoboFromFace.body()).isEqualTo(3);
    assertThat(chocoboFromFace.hands()).isEqualTo(4);
    assertThat(chocoboFromFace.legs()).isEqualTo(5);
    assertThat(chocoboFromFace.feet()).isEqualTo(6);
    assertThat(chocoboFromFace.mainWeapon()).isEqualTo(0);
    assertThat(chocoboFromFace.subShield()).isEqualTo(0);
    assertThat(chocoboFromFace.ranged()).isEqualTo(0);
    assertThat(chocoboFromFace.modelId()).isEqualTo(0);
    assertThat(chocoboFromFace.size()).isEqualTo(1);

    // Chocobo fallback to 32 when neither raceId nor faceId is in 32..36
    ActorAppearance chocoboFallback = ActorAppearance.fromRawNpcLook(
      "chocobo", 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0
    );
    assertThat(chocoboFallback.raceId()).isEqualTo(32);
    assertThat(chocoboFallback.faceId()).isEqualTo(0);

    // Standard & Automaton zero race/face/slots and keep modelId
    ActorAppearance standard = ActorAppearance.fromRawNpcLook(
      "standard", 3, 2,
      4108, 8204, 12300, 16396, 20492, 24576, 28672, 0,
      996, 1, 27, 32
    );
    assertThat(standard).isEqualTo(ActorAppearance.forStandardModel(996, 1, 27, 32));

    ActorAppearance automaton = ActorAppearance.fromRawNpcLook(
      "automaton", 3, 2,
      4108, 8204, 0, 0, 0, 0, 0, 0,
      1977, 0, 27, 32
    );
    assertThat(automaton.lookType()).isEqualTo(ActorLookType.AUTOMATON);
    assertThat(automaton.modelId()).isEqualTo(1977);
    assertThat(automaton.raceId()).isEqualTo(0);
    assertThat(automaton.head()).isEqualTo(0);

    // Unknown / blank / null look type -> NONE
    assertThat(ActorAppearance.fromRawNpcLook(null, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
      .isEqualTo(ActorAppearance.NONE);
    assertThat(ActorAppearance.fromRawNpcLook("door", 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
      .isEqualTo(ActorAppearance.NONE);
    assertThat(ActorLookType.fromDbValue("   ")).isEqualTo(ActorLookType.NONE);

    // Null lookType normalization in compact constructor & backwards-compatible constructors
    ActorAppearance nullNormalized = new ActorAppearance(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
    assertThat(nullNormalized.lookType()).isEqualTo(ActorLookType.NONE);

    EntitySpawnRecord legacySpawn = new EntitySpawnRecord(1L, "Legacy", (short) 1, (short) 0, 1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
    assertThat(legacySpawn.appearance()).isEqualTo(ActorAppearance.NONE);
    EntitySpawnRecord nullAppearanceSpawn = new EntitySpawnRecord(1L, "NullApp", (short) 1, (short) 0, 1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, null);
    assertThat(nullAppearanceSpawn.appearance()).isEqualTo(ActorAppearance.NONE);

    CharacterSummary legacySummary = new CharacterSummary(1L, "Old", 1, (short) 1, (short) 0, 230, 1, (short) 1, (short) 1, (short) 0);
    assertThat(legacySummary.faceId()).isEqualTo((short) 0);
    assertThat(legacySummary.hairId()).isEqualTo((short) 0);
    assertThat(legacySummary.sizeId()).isEqualTo((short) 1);
  }

  @Test
  @DisplayName("Security: ForySerializationProvider requires explicit class registration")
  void testRequireClassRegistration() {
    ThreadSafeFory fory = ForySerializationProvider.get();
    record UnregisteredClientDto(String secret) {}

    assertThatThrownBy(() -> fory.serialize(new UnregisteredClientDto("x")))
      .isInstanceOf(RuntimeException.class);
  }
}
