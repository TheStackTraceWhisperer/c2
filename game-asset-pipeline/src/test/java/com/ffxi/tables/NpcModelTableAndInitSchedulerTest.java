package com.ffxi.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.asset.config.FfxiPathConfig;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshOcclusion;
import com.ffxi.model.SkeletonMeshResource;
import com.ffxi.parser.DatParser;
import com.ffxi.parser.InitSchedulerParser;
import com.ffxi.parser.InitSchedulerParser.InitSchedulerResult;
import com.ffxi.parser.InitSchedulerParser.MotionOp;
import com.ffxi.parser.InitSchedulerParser.ScriptedLook;
import com.ffxi.parser.SectionType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NpcModelTableAndInitSchedulerTest {

    // -------------------------------------------------------------------------
    // Pure unit tests: NpcModelTable
    // -------------------------------------------------------------------------

    @Test
    void npcModelTableCoversAllFourRangesAndNegative() {
        assertThat(NpcModelTable.getNpcModelFileIndex(-1)).isEqualTo(-1);
        assertThat(NpcModelTable.getNpcModelFileIndex(-100)).isEqualTo(-1);

        // Range 1: 0..1499 -> 1300 + m
        assertThat(NpcModelTable.getNpcModelFileIndex(0)).isEqualTo(1300);
        assertThat(NpcModelTable.getNpcModelFileIndex(64)).isEqualTo(1364);
        assertThat(NpcModelTable.getNpcModelFileIndex(78)).isEqualTo(1378);
        assertThat(NpcModelTable.getNpcModelFileIndex(138)).isEqualTo(1438);
        assertThat(NpcModelTable.getNpcModelFileIndex(142)).isEqualTo(1442);
        assertThat(NpcModelTable.getNpcModelFileIndex(268)).isEqualTo(1568);
        assertThat(NpcModelTable.getNpcModelFileIndex(1499)).isEqualTo(2799);

        // Range 2: 1500..2999 -> 51795 + (m - 1500)
        assertThat(NpcModelTable.getNpcModelFileIndex(1500)).isEqualTo(51795);
        assertThat(NpcModelTable.getNpcModelFileIndex(1977)).isEqualTo(52272);
        assertThat(NpcModelTable.getNpcModelFileIndex(2920)).isEqualTo(53215);
        assertThat(NpcModelTable.getNpcModelFileIndex(2999)).isEqualTo(53294);

        // Range 3: 3000..3499 -> 96907 + m
        assertThat(NpcModelTable.getNpcModelFileIndex(3000)).isEqualTo(99907);
        assertThat(NpcModelTable.getNpcModelFileIndex(3499)).isEqualTo(100406);

        // Range 4: >= 3500 -> 98239 + m
        assertThat(NpcModelTable.getNpcModelFileIndex(3500)).isEqualTo(101739);
        assertThat(NpcModelTable.getNpcModelFileIndex(4000)).isEqualTo(102239);

        assertThat(NpcModelTable.getNpcModelPath(64, null)).isNull();
    }

    // -------------------------------------------------------------------------
    // Pure unit tests: RaceGenderConfig
    // -------------------------------------------------------------------------

    @Test
    void raceGenderConfigPredicatesAndFromRaceId() {
        assertThat(RaceGenderConfig.fromRaceId(0)).isEqualTo(RaceGenderConfig.HumeM);
        assertThat(RaceGenderConfig.fromRaceId(1)).isEqualTo(RaceGenderConfig.HumeM);
        assertThat(RaceGenderConfig.fromRaceId(2)).isEqualTo(RaceGenderConfig.HumeF);
        assertThat(RaceGenderConfig.fromRaceId(3)).isEqualTo(RaceGenderConfig.ElvaanM);
        assertThat(RaceGenderConfig.fromRaceId(4)).isEqualTo(RaceGenderConfig.ElvaanF);
        assertThat(RaceGenderConfig.fromRaceId(5)).isEqualTo(RaceGenderConfig.TaruM);
        assertThat(RaceGenderConfig.fromRaceId(6)).isEqualTo(RaceGenderConfig.TaruF);
        assertThat(RaceGenderConfig.fromRaceId(7)).isEqualTo(RaceGenderConfig.Mithra);
        assertThat(RaceGenderConfig.fromRaceId(8)).isEqualTo(RaceGenderConfig.Galka);
        assertThat(RaceGenderConfig.fromRaceId(29)).isEqualTo(RaceGenderConfig.MithraChild);
        assertThat(RaceGenderConfig.fromRaceId(30)).isEqualTo(RaceGenderConfig.HumeChildF);
        assertThat(RaceGenderConfig.fromRaceId(31)).isEqualTo(RaceGenderConfig.HumeChildM);
        assertThat(RaceGenderConfig.fromRaceId(32)).isEqualTo(RaceGenderConfig.Chocobo);
        assertThat(RaceGenderConfig.fromRaceId(33)).isEqualTo(RaceGenderConfig.ChocoboBlack);
        assertThat(RaceGenderConfig.fromRaceId(34)).isEqualTo(RaceGenderConfig.ChocoboBlue);
        assertThat(RaceGenderConfig.fromRaceId(35)).isEqualTo(RaceGenderConfig.ChocoboRed);
        assertThat(RaceGenderConfig.fromRaceId(36)).isEqualTo(RaceGenderConfig.ChocoboGreen);

        assertThat(RaceGenderConfig.fromRaceId(-1)).isNull();
        assertThat(RaceGenderConfig.fromRaceId(9)).isNull();
        assertThat(RaceGenderConfig.fromRaceId(28)).isNull();
        assertThat(RaceGenderConfig.fromRaceId(37)).isNull();

        assertThat(RaceGenderConfig.fromRaceIdOrDefault(0, RaceGenderConfig.Galka)).isEqualTo(RaceGenderConfig.HumeM);
        assertThat(RaceGenderConfig.fromRaceIdOrDefault(99, RaceGenderConfig.ElvaanF)).isEqualTo(RaceGenderConfig.ElvaanF);

        for (RaceGenderConfig cfg : RaceGenderConfig.values()) {
            if (cfg.index <= 8) {
                assertThat(cfg.isPlayableRace()).isTrue();
                assertThat(cfg.isNpcRace()).isFalse();
                assertThat(cfg.isChocobo()).isFalse();
            } else if (cfg.index <= 31) {
                assertThat(cfg.isPlayableRace()).isFalse();
                assertThat(cfg.isNpcRace()).isTrue();
                assertThat(cfg.isChocobo()).isFalse();
            } else {
                assertThat(cfg.isPlayableRace()).isFalse();
                assertThat(cfg.isNpcRace()).isTrue();
                assertThat(cfg.isChocobo()).isTrue();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pure unit tests: EquipmentModelTable holes and NPC/Chocobo slot rules
    // -------------------------------------------------------------------------

    @Test
    void equipmentModelTableHandlesHolesAndNpcSlotRules() {
        byte[] raw = new byte[EquipmentModelTable.TABLE_SIZE_BYTES];
        ByteReader writer = new ByteReader(raw);

        // HumeM (equipmentTableIndex = 1), Main slot (index = 6):
        // entry 0: offset=1000, count=10  -> modelId 0..9  => 1000..1009
        // entry 1: offset=0,    count=0   -> empty entry (skipped, does not advance count)
        // entry 2: offset=0,    count=5   -> HOLE for modelId 10..14 => -1 (MUST advance count!)
        // entry 3: offset=2000, count=4   -> modelId 15..18 => 2000..2003
        writeEquipEntry(writer, RaceGenderConfig.HumeM, ItemModelSlot.Main, 0, 1000, 10);
        writeEquipEntry(writer, RaceGenderConfig.HumeM, ItemModelSlot.Main, 1, 0, 0);
        writeEquipEntry(writer, RaceGenderConfig.HumeM, ItemModelSlot.Main, 2, 0, 5);
        writeEquipEntry(writer, RaceGenderConfig.HumeM, ItemModelSlot.Main, 3, 2000, 4);

        // HumeChildM (NPC race): populate Face, Body, Main, Sub, Range to verify Main/Sub/Range are blocked
        writeEquipEntry(writer, RaceGenderConfig.HumeChildM, ItemModelSlot.Face, 0, 3000, 64);
        writeEquipEntry(writer, RaceGenderConfig.HumeChildM, ItemModelSlot.Body, 0, 3100, 64);
        writeEquipEntry(writer, RaceGenderConfig.HumeChildM, ItemModelSlot.Main, 0, 3200, 16);
        writeEquipEntry(writer, RaceGenderConfig.HumeChildM, ItemModelSlot.Sub, 0, 3300, 16);
        writeEquipEntry(writer, RaceGenderConfig.HumeChildM, ItemModelSlot.Range, 0, 3400, 16);

        // Chocobo (NPC + chocobo): populate Face, Body, Main to verify Face & Main are blocked
        writeEquipEntry(writer, RaceGenderConfig.Chocobo, ItemModelSlot.Face, 0, 4000, 8);
        writeEquipEntry(writer, RaceGenderConfig.Chocobo, ItemModelSlot.Body, 0, 4100, 8);
        writeEquipEntry(writer, RaceGenderConfig.Chocobo, ItemModelSlot.Main, 0, 4200, 8);

        EquipmentModelTable table = EquipmentModelTable.parseTable(new ByteReader(raw));

        // Hole-safe assertions
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, 0)).isEqualTo(1000);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, 9)).isEqualTo(1009);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, 10)).isEqualTo(-1);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, 14)).isEqualTo(-1);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, 15)).isEqualTo(2000);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, 18)).isEqualTo(2003);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, 19)).isEqualTo(-1);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, ItemModelSlot.Main, -1)).isEqualTo(-1);
        assertThat(table.getEntryCount(RaceGenderConfig.HumeM, ItemModelSlot.Main)).isEqualTo(19);

        // NPC child slot rules
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeChildM, ItemModelSlot.Face, 20)).isEqualTo(3020);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeChildM, ItemModelSlot.Body, 20)).isEqualTo(3120);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeChildM, ItemModelSlot.Main, 0)).isEqualTo(-1);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeChildM, ItemModelSlot.Sub, 0)).isEqualTo(-1);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeChildM, ItemModelSlot.Range, 0)).isEqualTo(-1);
        assertThat(table.getEntryCount(RaceGenderConfig.HumeChildM, ItemModelSlot.Main)).isEqualTo(0);

        // Chocobo slot rules
        assertThat(table.getFileTableIndex(RaceGenderConfig.Chocobo, ItemModelSlot.Face, 0)).isEqualTo(-1);
        assertThat(table.getEntryCount(RaceGenderConfig.Chocobo, ItemModelSlot.Face)).isEqualTo(0);
        assertThat(table.getFileTableIndex(RaceGenderConfig.Chocobo, ItemModelSlot.Body, 3)).isEqualTo(4103);
        assertThat(table.getFileTableIndex(RaceGenderConfig.Chocobo, ItemModelSlot.Main, 0)).isEqualTo(-1);

        // Null guards
        assertThat(table.getFileTableIndex(null, ItemModelSlot.Body, 0)).isEqualTo(-1);
        assertThat(table.getFileTableIndex(RaceGenderConfig.HumeM, null, 0)).isEqualTo(-1);
        assertThat(table.getItemModelPath(RaceGenderConfig.HumeM, ItemModelSlot.Main, 0, null)).isNull();
    }

    @Test
    void characterIdToResourceIdReturnsNullUpperBodyAnimForNonPlayableRaces() {
        FileTableManager ftm = new FileTableManager();
        ByteReader dummyDll = new ByteReader(new byte[64]);
        assertThat(CharacterIdToResourceId.getUpperBodyAnimationPath(RaceGenderConfig.HumeChildM, dummyDll, ftm))
                .isNull();
        assertThat(CharacterIdToResourceId.getUpperBodyAnimationPath(RaceGenderConfig.Chocobo, dummyDll, ftm))
                .isNull();
    }

    // -------------------------------------------------------------------------
    // Pure unit tests: Synthetic InitSchedulerParser
    // -------------------------------------------------------------------------

    @Test
    void initSchedulerParsesBaseModelIdLookMotionOpsAndRecursiveWeaponHides() {
        // Chunk "init" sets:
        //   0x98 baseModelId = 1976
        //   0x8d..0x95 look: race=3, face=8, head=28, body=28, hands=28, legs=28, feet=28, main=5, sub=2
        //   0x8c motionOp = 1, 0x79 motionOp = 2, 0xa4 motionOp = 3, 0xa5 motionOp = 4
        //   0x3b calls "wof\0" (trailing NUL!) -> sets 0x75 wep4=1, calls "sub " (trailing space!)
        //   "sub " sets 0xa3 wep1=1 and 0x75 wep3=1 then 0x75 wep3=0 (re-shown)
        byte[] initRecords = concat(
                op12(0x98, 1976),
                op12(0x8d, 3),
                op12(0x8e, 8),
                op12(0x8f, 28),
                op12(0x90, 28),
                op12(0x91, 28),
                op12(0x92, 28),
                op12(0x93, 28),
                op12(0x94, 5),
                op12(0x95, 2),
                op12(0x8c, 1),
                op12(0x79, 2),
                op12(0xa4, 3),
                op12(0xa5, 4),
                opCall3b("wof\0"),
                new byte[]{0, 0, 0, 0}
        );

        byte[] wofRecords = concat(
                opWeaponHide(0x75, 1, (short) 4),
                opCall3b("sub "),
                new byte[]{0, 0, 0, 0}
        );

        byte[] subRecords = concat(
                opWeaponHide(0xa3, 1, (short) 1),
                opWeaponHide(0x75, 1, (short) 3),
                opWeaponHide(0x75, 0, (short) 3),
                new byte[]{0, 0, 0, 0}
        );

        byte[] dat = concat(
                section("init", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(initRecords)),
                section("wof\0", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(wofRecords)),
                section("sub ", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(subRecords)),
                section("end\0", SectionType.S00_End.getCode(), new byte[0])
        );

        InitSchedulerResult result = InitSchedulerParser.parse(dat);
        assertThat(result.baseModelId()).isEqualTo(Optional.of(1976));
        assertThat(result.scriptedLook()).isEqualTo(Optional.of(
                new ScriptedLook(3, 8, 28, 28, 28, 28, 28, 5, 2)));
        assertThat(result.motionOps()).containsExactly(
                new MotionOp(0x8c, 1),
                new MotionOp(0x79, 2),
                new MotionOp(0xa4, 3),
                new MotionOp(0xa5, 4)
        );
        assertThat(result.hiddenWeaponMask()).isEqualTo((1 << 1) | (1 << 4));
        assertThat(result.isWeaponHidden(1)).isTrue();
        assertThat(result.isWeaponHidden(4)).isTrue();
        assertThat(result.isWeaponHidden(3)).isFalse();
        assertThat(result.isWeaponHidden(2)).isFalse();
        assertThat(result.isWeaponHidden(-1)).isFalse();
    }

    @Test
    void initSchedulerStopsRecursionAtMaxDepthFour() {
        // Depth 0: init -> c1 (depth 1) -> c2 (depth 2) -> c3 (depth 3) -> c4 (depth 4, runs 0x75 wep5=1, does NOT recurse to c5)
        byte[] dat = concat(
                section("init", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(concat(opCall3b("c1  "), new byte[4]))),
                section("c1\0\0", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(concat(opCall3b("c2\0\0"), new byte[4]))),
                section("c2  ", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(concat(opCall3b("c3  "), new byte[4]))),
                section("c3  ", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(concat(opCall3b("c4  "), new byte[4]))),
                section("c4  ", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(concat(
                        opWeaponHide(0x75, 1, (short) 5),
                        opCall3b("c5  "),
                        new byte[4]))),
                section("c5  ", SectionType.S07_EffectRoutine.getCode(), schedulerPayload(concat(
                        opWeaponHide(0x75, 1, (short) 6),
                        new byte[4])))
        );

        InitSchedulerResult result = InitSchedulerParser.parse(dat);
        assertThat(result.isWeaponHidden(5)).isTrue();
        assertThat(result.isWeaponHidden(6)).isFalse();
        assertThat(result.hiddenWeaponMask()).isEqualTo(1 << 5);
    }

    @Test
    void initSchedulerEmptyAndNullReturnEmpty() {
        assertThat(InitSchedulerParser.parse(null)).isEqualTo(InitSchedulerResult.EMPTY);
        assertThat(InitSchedulerParser.parse(new byte[0])).isEqualTo(InitSchedulerResult.EMPTY);
        assertThat(InitSchedulerParser.parse(new byte[8])).isEqualTo(InitSchedulerResult.EMPTY);
    }

    // -------------------------------------------------------------------------
    // Retail DAT tests (guarded with assumeTrue)
    // -------------------------------------------------------------------------

    @Test
    void retailModelIndexCacheLookupsNpcModelsInitSchedulerAndOcclusion() throws IOException {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        assumeTrue(Files.isDirectory(ffxiRoot) && Files.isRegularFile(ffxiRoot.resolve("FFXiMain.dll")),
                "Retail FFXI installation not found at " + ffxiRoot);

        FfxiModelIndex.clearCache();
        FfxiModelIndex index = FfxiModelIndex.getOrLoad(ffxiRoot);
        FfxiModelIndex second = FfxiModelIndex.getOrLoad(ffxiRoot.resolve("."));
        assertSame(index, second, "FfxiModelIndex.getOrLoad must return cached instance per normalized path");
        assertThat(index.ffxiRoot()).isEqualTo(ffxiRoot.toAbsolutePath().normalize());

        // Aubejart / Ephauge: ElvaanM face 8/0, armor 28
        assertExistingDat(index.getSkeletonPath(RaceGenderConfig.ElvaanM));
        assertExistingDat(index.getUpperBodyAnimationPath(RaceGenderConfig.ElvaanM));
        String elvaanMFace8 = index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Face, 8);
        String elvaanMFace0 = index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Face, 0);
        String elvaanMHead28 = index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Head, 28);
        String elvaanMBody28 = index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Body, 28);
        assertExistingDat(elvaanMFace8);
        assertExistingDat(elvaanMFace0);
        assertExistingDat(elvaanMHead28);
        assertExistingDat(elvaanMBody28);

        // Ceraule: ElvaanM face 2, armor 12
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Face, 2));
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Head, 12));
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Body, 12));
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Hands, 12));
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Legs, 12));
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Feet, 12));

        // Machielle: ElvaanF face 4, body 19
        assertExistingDat(index.getSkeletonPath(RaceGenderConfig.ElvaanF));
        assertExistingDat(index.getUpperBodyAnimationPath(RaceGenderConfig.ElvaanF));
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanF, ItemModelSlot.Face, 4));
        assertExistingDat(index.getEquipmentModelPath(RaceGenderConfig.ElvaanF, ItemModelSlot.Body, 19));

        // Leuveret: race 31 HumeChildM, face 20, armor 20, upperBodyAnimationPath == null
        RaceGenderConfig leuveretRace = RaceGenderConfig.fromRaceId(31);
        assertThat(leuveretRace).isEqualTo(RaceGenderConfig.HumeChildM);
        assertExistingDat(index.getSkeletonPath(leuveretRace));
        assertThat(index.getUpperBodyAnimationPath(leuveretRace)).isNull();
        assertExistingDat(index.getEquipmentModelPath(leuveretRace, ItemModelSlot.Face, 20));
        assertExistingDat(index.getEquipmentModelPath(leuveretRace, ItemModelSlot.Head, 20));
        assertExistingDat(index.getEquipmentModelPath(leuveretRace, ItemModelSlot.Body, 20));
        assertExistingDat(index.getEquipmentModelPath(leuveretRace, ItemModelSlot.Hands, 20));
        assertExistingDat(index.getEquipmentModelPath(leuveretRace, ItemModelSlot.Legs, 20));
        assertExistingDat(index.getEquipmentModelPath(leuveretRace, ItemModelSlot.Feet, 20));
        assertThat(index.getEquipmentModelPath(leuveretRace, ItemModelSlot.Main, 0)).isNull();

        // Harmodios: race 0 -> HumeM
        RaceGenderConfig harmodiosRace = RaceGenderConfig.fromRaceId(0);
        assertThat(harmodiosRace).isEqualTo(RaceGenderConfig.HumeM);
        assertExistingDat(index.getSkeletonPath(harmodiosRace));
        assertExistingDat(index.getUpperBodyAnimationPath(harmodiosRace));

        // Chocobo: race 32, Face slot null, Body/Head/Feet non-null, upperBodyAnimationPath == null
        RaceGenderConfig chocoboRace = RaceGenderConfig.fromRaceId(32);
        assertThat(chocoboRace).isEqualTo(RaceGenderConfig.Chocobo);
        assertExistingDat(index.getSkeletonPath(chocoboRace));
        assertThat(index.getUpperBodyAnimationPath(chocoboRace)).isNull();
        assertThat(index.getEquipmentModelPath(chocoboRace, ItemModelSlot.Face, 0)).isNull();
        assertExistingDat(index.getEquipmentModelPath(chocoboRace, ItemModelSlot.Head, 0));
        assertExistingDat(index.getEquipmentModelPath(chocoboRace, ItemModelSlot.Body, 0));
        assertExistingDat(index.getEquipmentModelPath(chocoboRace, ItemModelSlot.Feet, 0));

        // Named NPC models
        String coruaPath = index.getNpcModelPath(142);
        assertThat(normalizeSeparators(coruaPath)).endsWith("ROM/3/116.DAT");
        assertExistingDat(coruaPath);

        String raimbroyPath = index.getNpcModelPath(138);
        assertThat(normalizeSeparators(raimbroyPath)).endsWith("ROM/3/112.DAT");
        assertExistingDat(raimbroyPath);

        String aldoPath = index.getNpcModelPath(78);
        assertThat(normalizeSeparators(aldoPath)).endsWith("ROM/3/52.DAT");
        assertExistingDat(aldoPath);

        // Trion: model 64 -> ROM/3/38.DAT, InitSchedulerParser returns hiddenWeaponMask == (1 << 4)
        String trionPath = index.getNpcModelPath(64);
        assertThat(normalizeSeparators(trionPath)).endsWith("ROM/3/38.DAT");
        assertExistingDat(trionPath);
        InitSchedulerResult trionInit = InitSchedulerParser.parse(Files.readAllBytes(Path.of(trionPath)));
        assertThat(trionInit.hiddenWeaponMask()).isEqualTo(1 << 4);
        assertThat(trionInit.isWeaponHidden(4)).isTrue();

        // Model 1977 -> ROM/162/111.DAT, InitSchedulerParser returns baseModelId == Optional.of(1976)
        String model1977Path = index.getNpcModelPath(1977);
        assertThat(normalizeSeparators(model1977Path)).endsWith("ROM/162/111.DAT");
        assertExistingDat(model1977Path);
        InitSchedulerResult model1977Init = InitSchedulerParser.parse(Files.readAllBytes(Path.of(model1977Path)));
        assertThat(model1977Init.baseModelId()).isEqualTo(Optional.of(1976));

        // Real retail helmet-hair occlusion on ElvaanM face 8:
        // - head 28 has occludeType=0x01 (open top: keeps hair/face visible)
        // - head 12 (Ceraule guard helmet) has occludeType=0x02 (occludes hair1, displayType=1)
        // - head 22 (full visor helmet) has occludeType=0x05 (occludes hair1/hair3/face, displayType=1,3,4)
        List<SkeletonMeshResource> face8Resources = new ArrayList<>();
        collectSkeletonMeshes(parseDat(Path.of(elvaanMFace8)).rootDirectory(), face8Resources);

        List<SkeletonMeshResource> withHead28 = new ArrayList<>(face8Resources);
        collectSkeletonMeshes(parseDat(Path.of(elvaanMHead28)).rootDirectory(), withHead28);
        int totalWithHead28 = withHead28.stream().mapToInt(r -> r.meshes().size()).sum();
        List<SkeletonMeshMesh> visibleWithHead28 = SkeletonMeshOcclusion.filterVisibleMeshes(
                withHead28, SkeletonMeshOcclusion.DEFAULT_HIDDEN_WEAPON_MASK);
        assertThat(visibleWithHead28).hasSize(totalWithHead28);

        String elvaanMHead12 = index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Head, 12);
        List<SkeletonMeshResource> withHead12 = new ArrayList<>(face8Resources);
        collectSkeletonMeshes(parseDat(Path.of(elvaanMHead12)).rootDirectory(), withHead12);
        int totalWithHead12 = withHead12.stream().mapToInt(r -> r.meshes().size()).sum();
        List<SkeletonMeshMesh> visibleWithHead12 = SkeletonMeshOcclusion.filterVisibleMeshes(
                withHead12, SkeletonMeshOcclusion.DEFAULT_HIDDEN_WEAPON_MASK);
        assertThat(totalWithHead12).isGreaterThan(0);
        assertThat(visibleWithHead12.size()).isGreaterThan(0).isLessThan(totalWithHead12);

        String elvaanMHead22 = index.getEquipmentModelPath(RaceGenderConfig.ElvaanM, ItemModelSlot.Head, 22);
        assertExistingDat(elvaanMHead22);
        List<SkeletonMeshResource> withHead22 = new ArrayList<>(face8Resources);
        collectSkeletonMeshes(parseDat(Path.of(elvaanMHead22)).rootDirectory(), withHead22);
        int totalWithHead22 = withHead22.stream().mapToInt(r -> r.meshes().size()).sum();
        List<SkeletonMeshMesh> visibleWithHead22 = SkeletonMeshOcclusion.filterVisibleMeshes(
                withHead22, SkeletonMeshOcclusion.DEFAULT_HIDDEN_WEAPON_MASK);
        assertThat(visibleWithHead22.size()).isGreaterThan(0).isLessThan(totalWithHead22);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void assertExistingDat(String path) {
        assertThat(path).isNotNull();
        assertThat(Files.isRegularFile(Path.of(path)))
                .as("Expected DAT file to exist: %s", path)
                .isTrue();
    }

    private static String normalizeSeparators(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static DatResourceGraph parseDat(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return DatParser.parseResourceGraph(new ByteReader(bytes, path.getFileName().toString()));
    }

    private static void collectSkeletonMeshes(DirectoryResource dir, List<SkeletonMeshResource> out) {
        for (ResourceNode child : dir.children()) {
            if (child instanceof SkeletonMeshResource mesh) {
                out.add(mesh);
            } else if (child instanceof DirectoryResource subDir) {
                collectSkeletonMeshes(subDir, out);
            }
        }
    }

    private static void writeEquipEntry(
            ByteReader writer,
            RaceGenderConfig race,
            ItemModelSlot slot,
            int entryIdx,
            int tableOffset,
            int entryCount
    ) {
        int pos = EquipmentModelTable.RACE_TABLE_SIZE_BYTES * (race.equipmentTableIndex - 1)
                + EquipmentModelTable.SLOT_TABLE_STRIDE_BYTES * slot.index
                + EquipmentModelTable.ENTRY_SIZE_BYTES * entryIdx;
        writer.setPosition(pos);
        writer.write32(tableOffset);
        writer.write32(entryCount);
    }

    private static byte[] schedulerPayload(byte[] records) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // 16 bytes header + 4 bytes offset to record stream (= 20)
        for (int i = 0; i < 16; i++) {
            o.write(0);
        }
        le32(o, 20);
        o.writeBytes(records);
        return o.toByteArray();
    }

    private static byte[] op12(int op, int value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(12);
        o.write(op & 0xFF);
        o.write(3); // 3 * 4 = 12 bytes
        le16(o, 0);
        le32(o, 0);
        le32(o, value);
        return o.toByteArray();
    }

    private static byte[] opCall3b(String callee4) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(12);
        o.write(0x3b);
        o.write(3); // 12 bytes
        le16(o, 0);
        le32(o, 0);
        fixedAscii(o, callee4, 4);
        return o.toByteArray();
    }

    private static byte[] opWeaponHide(int op, int hideFlag, short wepIndex) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(16);
        o.write(op & 0xFF);
        o.write(4); // 4 * 4 = 16 bytes (>= 14)
        le16(o, 0);
        le32(o, 0);
        le32(o, hideFlag);
        le16(o, wepIndex & 0xFFFF);
        le16(o, 0);
        return o.toByteArray();
    }

    private static byte[] section(String id, int typeCode, byte[] payload) {
        int paddedPayload = (payload.length + 0x0F) & ~0x0F;
        int totalSize = 0x10 + paddedPayload;
        int meta = (typeCode & 0x7F) | (((totalSize / 0x10) & 0xFFFFF) << 7);
        ByteArrayOutputStream o = new ByteArrayOutputStream(totalSize);
        fixedAscii(o, id, 4);
        le32(o, meta);
        le32(o, 0);
        le32(o, 0);
        o.writeBytes(payload);
        while (o.size() < totalSize) {
            o.write(0);
        }
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            o.writeBytes(part);
        }
        return o.toByteArray();
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xFFFF);
        le16(o, v >>> 16);
    }

    private static void fixedAscii(ByteArrayOutputStream o, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < len; i++) {
            o.write(i < b.length ? b[i] : 0);
        }
    }
}
