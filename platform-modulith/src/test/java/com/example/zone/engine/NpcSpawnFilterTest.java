package com.example.zone.engine;

import com.example.server.entity.NpcRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NpcSpawnFilterTest {

  private static final Pattern NPC_ROW_PATTERN = Pattern.compile(
      "^\\s*\\((\\d+),\\s*'((?:[^']|'')*)',\\s*([-0-9.]+),\\s*([-0-9.]+),\\s*([-0-9.]+),"
          + "\\s*(-?\\d+),\\s*(\\d+),\\s*'((?:[^']|'')*)',\\s*(-?\\d+),\\s*(-?\\d+),"
          + "\\s*(-?\\d+),\\s*(-?\\d+),\\s*(NULL|'[^']*'),\\s*(-?\\d+),\\s*(-?\\d+),"
          + "\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),"
          + "\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(true|false)\\)");

  private static final Pattern ZONE_ID_PATTERN = Pattern.compile(
      "^\\s*\\(\\s*(\\d+)\\s*,\\s*'[^']*'");

  @Test
  @DisplayName("Valid equipped, standard, chocobo, and automaton NPC records pass shouldSpawn (including animation_sub=1)")
  void testValidSpawnableNpcTypes() {
    NpcRecord equippedGuard = createNpc(
        17719297L, "Ceraule", -86.077f, 1.999f, -34.793f,
        230, "normal", (short) 1, "equipped", 0);
    NpcRecord standardAldo = createNpc(
        17780743L, "Aldo", 21.049f, 3.899f, -61.530f,
        245, "normal", (short) 0, "standard", 78);
    NpcRecord standardWell = createNpc(
        17719481L, "Well", -25.189f, -2.000f, 39.299f,
        230, "normal", (short) 1, "standard", 996);
    NpcRecord chocobo = createNpc(
        17719401L, "Chocobo", 9.956f, -3.000f, -110.892f,
        230, "normal", (short) 1, "chocobo", 0);
    NpcRecord automaton = createNpc(
        17799901L, "Automaton", 5.0f, 0.0f, 5.0f,
        245, "NORMAL", (short) 0, "AUTOMATON", 900);

    assertThat(NpcSpawnFilter.shouldSpawn(equippedGuard)).isTrue();
    assertThat(NpcSpawnFilter.shouldSpawn(standardAldo)).isTrue();
    assertThat(NpcSpawnFilter.shouldSpawn(standardWell)).isTrue();
    assertThat(NpcSpawnFilter.shouldSpawn(chocobo)).isTrue();
    assertThat(NpcSpawnFilter.shouldSpawn(automaton)).isTrue();
  }

  @Test
  @DisplayName("Null record and non-normal status values (cutscene_only, status_4, status_5, null) are rejected")
  void testRejectsNullAndNonNormalStatus() {
    assertThat(NpcSpawnFilter.shouldSpawn(null)).isFalse();

    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        1L, "CutsceneActor", 10f, 0f, 10f, 230, "cutscene_only", (short) 0, "equipped", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        2L, "Status4Actor", 10f, 0f, 10f, 245, "status_4", (short) 0, "equipped", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        3L, "Status5Actor", 10f, 0f, 10f, 245, "status_5", (short) 0, "standard", 100))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        4L, "NullStatusActor", 10f, 0f, 10f, 230, null, (short) 0, "equipped", 0))).isFalse();
  }

  @Test
  @DisplayName("Invalid lookType values (door, ship, null, unknown) and standard/automaton with modelId <= 0 are rejected")
  void testRejectsInvalidLookTypesAndZeroModelStandardOrAutomaton() {
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        10L, "_6r0", 12.0f, 0.0f, 15.0f, 230, "normal", (short) 0, "door", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        11L, "Airship_Triggers", 0.0f, 0.0f, 0.0f, 245, "normal", (short) 0, "ship", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        12L, "NullLookNpc", 1.0f, 0.0f, 1.0f, 230, "normal", (short) 0, null, 100))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        13L, "UnknownLookNpc", 1.0f, 0.0f, 1.0f, 230, "normal", (short) 0, "item_object", 100))).isFalse();

    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        14L, "ZeroModelStandard", 1.0f, 0.0f, 1.0f, 230, "normal", (short) 0, "standard", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        15L, "NegativeModelStandard", 1.0f, 0.0f, 1.0f, 230, "normal", (short) 0, "standard", -1))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        16L, "ZeroModelAutomaton", 1.0f, 0.0f, 1.0f, 230, "normal", (short) 0, "automaton", 0))).isFalse();
  }

  @Test
  @DisplayName("Null, empty, whitespace, and 'blank' names (case-insensitive) are rejected")
  void testRejectsBlankAndMissingNames() {
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        20L, "blank", 0.0f, 0.0f, 0.0f, 230, "normal", (short) 0, "standard", 963))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        21L, "  BLANK  ", 0.0f, 0.0f, 0.0f, 230, "normal", (short) 0, "equipped", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        22L, "", 1.0f, 1.0f, 1.0f, 230, "normal", (short) 0, "equipped", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        23L, "   ", 1.0f, 1.0f, 1.0f, 230, "normal", (short) 0, "equipped", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        24L, null, 1.0f, 1.0f, 1.0f, 230, "normal", (short) 0, "equipped", 0))).isFalse();
  }

  @Test
  @DisplayName("Non-finite coordinates (NaN or Infinity) are rejected")
  void testRejectsNonFiniteCoordinates() {
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        30L, "NanX", Float.NaN, 1.0f, 1.0f, 230, "normal", (short) 0, "equipped", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        31L, "InfY", 1.0f, Float.POSITIVE_INFINITY, 1.0f, 230, "normal", (short) 0, "equipped", 0))).isFalse();
    assertThat(NpcSpawnFilter.shouldSpawn(createNpc(
        32L, "NegInfZ", 1.0f, 1.0f, Float.NEGATIVE_INFINITY, 230, "normal", (short) 0, "equipped", 0))).isFalse();
  }

  @Test
  @DisplayName("V2__npc_list.sql integrity & regression: 5,087 unique rows, valid zone IDs, Aldo only in Zone 245, clean Zone 230 & 245 spawns")
  void testV2NpcListSqlIntegrityAndZoneFiltering() throws Exception {
    Set<Integer> seededZoneIds = new HashSet<>();
    seededZoneIds.addAll(parseZoneIds("/db/migration/V1__init_schema.sql"));
    seededZoneIds.addAll(parseZoneIds("/db/migration/V2__npc_list.sql"));
    assertThat(seededZoneIds).isNotEmpty();

    List<NpcRecord> allRows = parseV2NpcList("/db/migration/V2__npc_list.sql");
    assertThat(allRows).hasSize(5087);

    Set<Long> uniqueIds = new HashSet<>();
    for (NpcRecord r : allRows) {
      assertThat(uniqueIds.add(r.id()))
          .as("Duplicate NPC primary key ID %d", r.id())
          .isTrue();
      assertThat(seededZoneIds)
          .as("Zone ID %d for NPC %d must exist in game.zones", r.zoneId(), r.id())
          .contains(r.zoneId());
      int encodedZoneId = (int) ((r.id() >> 12) & 0xFFFL);
      assertThat(encodedZoneId)
          .as("Encoded zone ID in NPC ID %d must match zone_id %d", r.id(), r.zoneId())
          .isEqualTo(r.zoneId());
      assertThat(Float.isFinite(r.posX()) && Float.isFinite(r.posY()) && Float.isFinite(r.posZ()))
          .as("Coordinates for NPC %d must be finite", r.id())
          .isTrue();
    }

    // Verify Aldo (id=17780743, model_id=78) exists ONLY in Zone 245 (Lower Jeuno), NOT Zone 230
    List<NpcRecord> aldoRecords = allRows.stream()
        .filter(r -> "Aldo".equalsIgnoreCase(r.name()) || r.id() == 17780743L)
        .toList();
    assertThat(aldoRecords).hasSize(1);
    NpcRecord aldo = aldoRecords.get(0);
    assertThat(aldo.id()).isEqualTo(17780743L);
    assertThat(aldo.zoneId()).isEqualTo(245);
    assertThat(aldo.modelId()).isEqualTo(78);
    assertThat(NpcSpawnFilter.shouldSpawn(aldo)).isTrue();

    // Verify Zone 230 (Southern San d'Oria) filtering
    List<NpcRecord> zone230All = allRows.stream().filter(r -> r.zoneId() == 230).toList();
    List<NpcRecord> zone230Spawned = zone230All.stream().filter(NpcSpawnFilter::shouldSpawn).toList();
    assertThat(zone230All).hasSize(725);
    assertThat(zone230Spawned).hasSize(194);
    assertCleanSpawnSet(zone230Spawned);
    Set<String> zone230Names = zone230Spawned.stream().map(NpcRecord::name).collect(Collectors.toSet());
    assertThat(zone230Names)
        .contains("Ceraule", "Aubejart", "Ephauge", "Well", "Chocobo")
        .doesNotContain("Aldo", "blank");

    // Verify Zone 245 (Lower Jeuno) filtering
    List<NpcRecord> zone245All = allRows.stream().filter(r -> r.zoneId() == 245).toList();
    List<NpcRecord> zone245Spawned = zone245All.stream().filter(NpcSpawnFilter::shouldSpawn).toList();
    assertThat(zone245All).hasSize(336);
    assertThat(zone245Spawned).hasSize(148);
    assertCleanSpawnSet(zone245Spawned);
    Set<String> zone245Names = zone245Spawned.stream().map(NpcRecord::name).collect(Collectors.toSet());
    assertThat(zone245Names)
        .contains("Aldo", "Chocobo")
        .doesNotContain("blank");
  }

  private static void assertCleanSpawnSet(List<NpcRecord> spawned) {
    assertThat(spawned).allSatisfy(r -> {
      assertThat(r.status()).isEqualToIgnoringCase("normal");
      assertThat(r.lookType()).isNotNull();
      assertThat(r.lookType().toLowerCase())
          .isIn("equipped", "standard", "chocobo", "automaton")
          .isNotIn("door", "ship");
      if ("standard".equalsIgnoreCase(r.lookType()) || "automaton".equalsIgnoreCase(r.lookType())) {
        assertThat(r.modelId()).isGreaterThan(0);
      }
      assertThat(r.name()).isNotBlank();
      assertThat(r.name().trim()).isNotEqualToIgnoringCase("blank");
    });
  }

  private static Set<Integer> parseZoneIds(String resourcePath) throws Exception {
    Set<Integer> zoneIds = new HashSet<>();
    boolean inZonesInsert = false;
    try (InputStream in = NpcSpawnFilterTest.class.getResourceAsStream(resourcePath);
         BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains("INSERT INTO game.zones")) {
          inZonesInsert = true;
          continue;
        }
        if (inZonesInsert) {
          Matcher m = ZONE_ID_PATTERN.matcher(line);
          if (m.find()) {
            zoneIds.add(Integer.parseInt(m.group(1)));
          }
          if (line.contains(";")) {
            inZonesInsert = false;
          }
        }
      }
    }
    return zoneIds;
  }

  private static List<NpcRecord> parseV2NpcList(String resourcePath) throws Exception {
    List<NpcRecord> records = new ArrayList<>(5100);
    try (InputStream in = NpcSpawnFilterTest.class.getResourceAsStream(resourcePath);
         BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher m = NPC_ROW_PATTERN.matcher(line);
        if (m.find()) {
          long id = Long.parseLong(m.group(1));
          String name = m.group(2).replace("''", "'");
          float posX = Float.parseFloat(m.group(3));
          float posY = Float.parseFloat(m.group(4));
          float posZ = Float.parseFloat(m.group(5));
          short posRot = Short.parseShort(m.group(6));
          int zoneId = Integer.parseInt(m.group(7));
          String status = m.group(8).replace("''", "'");
          int entityFlags = Integer.parseInt(m.group(9));
          short animationSub = Short.parseShort(m.group(10));
          short namePrefix = Short.parseShort(m.group(11));
          int movingUnsigned = Integer.parseInt(m.group(12));
          assertThat(movingUnsigned).isBetween(0, 65535);
          short moving = (short) movingUnsigned;
          String rawLookType = m.group(13);
          String lookType = "NULL".equals(rawLookType)
              ? null
              : rawLookType.substring(1, rawLookType.length() - 1).replace("''", "'");
          short raceId = Short.parseShort(m.group(14));
          short faceId = Short.parseShort(m.group(15));
          int head = Integer.parseInt(m.group(16));
          int body = Integer.parseInt(m.group(17));
          int hands = Integer.parseInt(m.group(18));
          int legs = Integer.parseInt(m.group(19));
          int feet = Integer.parseInt(m.group(20));
          int mainWeapon = Integer.parseInt(m.group(21));
          int subShield = Integer.parseInt(m.group(22));
          int ranged = Integer.parseInt(m.group(23));
          int modelId = Integer.parseInt(m.group(24));
          boolean widescan = Boolean.parseBoolean(m.group(25));

          records.add(new NpcRecord(
              id, name, posX, posY, posZ, posRot, zoneId, status,
              entityFlags, animationSub, namePrefix, moving, lookType,
              raceId, faceId, head, body, hands, legs, feet,
              mainWeapon, subShield, ranged, modelId, widescan));
        }
      }
    }
    return records;
  }

  private static NpcRecord createNpc(
      long id,
      String name,
      float posX,
      float posY,
      float posZ,
      int zoneId,
      String status,
      short animationSub,
      String lookType,
      int modelId) {
    return new NpcRecord(
        id, name, posX, posY, posZ, (short) 64,
        zoneId, status, 27, animationSub, (short) 32, (short) 1,
        lookType, (short) 1, (short) 1,
        0, 0, 0, 0, 0, 0, 0, 0, modelId, true);
  }
}
