package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.ActorLookType;
import com.ffxi.io.ByteReader;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshResource;
import com.ffxi.parser.DatParser;
import com.ffxi.parser.InitSchedulerParser;
import com.ffxi.tables.FfxiModelIndex;
import com.ffxi.tables.ItemModelSlot;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CharacterModelLoaderActorTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();

    @Test
    @DisplayName("AppearanceCacheKey deduplicates identical actors differing only in animationSub, entityFlags, or namePrefix")
    void testAppearanceCacheKeyDeduplication() {
        ActorAppearance guardA = ActorAppearance.fromRawNpcLook(
                "equipped", 3, 8,
                4124, 8220, 12316, 16412, 20508, 24576, 28672, 0,
                0, 1, 27, 32
        );
        ActorAppearance guardB = ActorAppearance.fromRawNpcLook(
                "equipped", 3, 8,
                4124, 8220, 12316, 16412, 20508, 24576, 28672, 0,
                0, 4, 4194331, 0
        );
        ActorAppearance guardDifferentFace = ActorAppearance.fromRawNpcLook(
                "equipped", 3, 0,
                4124, 8220, 12316, 16412, 20508, 24576, 28672, 0,
                0, 1, 27, 32
        );

        var keyA = CharacterAssetCache.AppearanceCacheKey.from(guardA);
        var keyB = CharacterAssetCache.AppearanceCacheKey.from(guardB);
        var keyDifferentFace = CharacterAssetCache.AppearanceCacheKey.from(guardDifferentFace);

        assertEquals(keyA, keyB, "Identical equipment/race/face with differing animationSub/entityFlags/namePrefix must share cache key");
        assertEquals(keyA.hashCode(), keyB.hashCode());
        assertNotEquals(keyA, keyDifferentFace, "Different face must produce distinct cache key");

        ActorAppearance stdA = ActorAppearance.forStandardModel(78, 0, 27, 32);
        ActorAppearance stdB = ActorAppearance.forStandardModel(78, 2, 2075, 0);
        assertEquals(
                CharacterAssetCache.AppearanceCacheKey.from(stdA),
                CharacterAssetCache.AppearanceCacheKey.from(stdB),
                "Identical standard model with differing animationSub/entityFlags/namePrefix must share cache key"
        );

        assertEquals(
                CharacterAssetCache.AppearanceCacheKey.from(null),
                CharacterAssetCache.AppearanceCacheKey.from(ActorAppearance.NONE),
                "null and NONE appearances must normalize identically"
        );
        assertEquals(
                CharacterAssetCache.AppearanceCacheKey.from(ActorAppearance.NONE),
                CharacterAssetCache.AppearanceCacheKey.from(ActorAppearance.forPlayer(1, 0, 1)),
                "NONE appearance must normalize to HumeM default"
        );
    }

    @Test
    @DisplayName("Retail: loadActor loads equipped ElvaanM Aubejart (face 8, armor 28)")
    void testLoadAubejart() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance aubejart = ActorAppearance.fromRawNpcLook(
                "equipped", 3, 8,
                4124, 8220, 12316, 16412, 20508, 24576, 28672, 0,
                0, 1, 27, 32
        );
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, aubejart);
        assertValidActor(loaded, "Aubejart");
        assertEquals(8, loaded.loadedModelIds().get(ItemModelSlot.Face));
        assertEquals(28, loaded.loadedModelIds().get(ItemModelSlot.Head));
        assertEquals(28, loaded.loadedModelIds().get(ItemModelSlot.Body));
        assertEquals(28, loaded.loadedModelIds().get(ItemModelSlot.Hands));
        assertEquals(28, loaded.loadedModelIds().get(ItemModelSlot.Legs));
        assertEquals(28, loaded.loadedModelIds().get(ItemModelSlot.Feet));
    }

    @Test
    @DisplayName("Retail: loadActor loads equipped ElvaanM Ceraule (face 2, armor 12)")
    void testLoadCeraule() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance ceraule = ActorAppearance.fromRawNpcLook(
                "equipped", 3, 2,
                4108, 8204, 12300, 16396, 20492, 24576, 28672, 0,
                0, 1, 27, 32
        );
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, ceraule);
        assertValidActor(loaded, "Ceraule");
        assertEquals(2, loaded.loadedModelIds().get(ItemModelSlot.Face));
        assertEquals(12, loaded.loadedModelIds().get(ItemModelSlot.Head));
        assertEquals(12, loaded.loadedModelIds().get(ItemModelSlot.Body));
    }

    @Test
    @DisplayName("Retail: loadActor loads equipped ElvaanF Machielle (face 4, body 19)")
    void testLoadMachielle() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance machielle = ActorAppearance.fromRawNpcLook(
                "equipped", 4, 4,
                4096, 8211, 12288, 16392, 20488, 24576, 28672, 0,
                0, 1, 27, 32
        );
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, machielle);
        assertValidActor(loaded, "Machielle");
        assertEquals(4, loaded.loadedModelIds().get(ItemModelSlot.Face));
        assertEquals(19, loaded.loadedModelIds().get(ItemModelSlot.Body));
    }

    @Test
    @DisplayName("Retail: loadActor loads HumeChildM Leuveret (race 31, face 20, armor 20) without upper-body dummy DAT")
    void testLoadLeuveretChild() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance leuveret = ActorAppearance.fromRawNpcLook(
                "equipped", 31, 20,
                4116, 8212, 12308, 16404, 20500, 24576, 28672, 0,
                0, 1, 27, 32
        );
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, leuveret);
        assertValidActor(loaded, "Leuveret");
        assertTrue(loaded.slot1Animations().isEmpty(), "Non-playable child race 31 must not load upper-body animation DAT");
        assertFalse(loaded.loadedModelIds().containsKey(ItemModelSlot.Main), "NPC child race must skip weapon slots");
    }

    @Test
    @DisplayName("Retail: loadActor loads standard NPC Corua (model 142)")
    void testLoadCoruaStandard() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance corua = ActorAppearance.forStandardModel(142, 0, 27, 32);
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, corua);
        assertValidActor(loaded, "Corua");
        assertFalse(loaded.slot0Animations().isEmpty(), "Corua must have standard model animations");
    }

    @Test
    @DisplayName("Retail: loadActor loads standard NPC Aldo (model 78)")
    void testLoadAldoStandard() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance aldo = ActorAppearance.forStandardModel(78, 0, 27, 32);
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, aldo);
        assertValidActor(loaded, "Aldo");
        assertFalse(loaded.slot0Animations().isEmpty(), "Aldo must have standard model animations");
    }

    @Test
    @DisplayName("Retail: loadActor loads standard NPC Trion (model 64) and excludes hidden wep4 meshes")
    void testLoadTrionStandardExcludesWep4() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        FfxiModelIndex index = FfxiModelIndex.getOrLoad(FFXI_ROOT);
        String datPath = index.getNpcModelPath(64);
        assertNotNull(datPath, "Trion model 64 DAT path must resolve");
        byte[] raw = Files.readAllBytes(Path.of(datPath));
        InitSchedulerParser.InitSchedulerResult init = InitSchedulerParser.parse(raw);
        assertTrue(init.isWeaponHidden(4), "Trion init routine must hide wep4");

        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(raw, "trion64.dat"));
        List<SkeletonMeshResource> allResources = new ArrayList<>();
        collectMeshes(graph.rootDirectory(), allResources);

        int wep4MeshCount = 0;
        int totalRawMeshes = 0;
        for (SkeletonMeshResource res : allResources) {
            totalRawMeshes += res.meshes().size();
            if ("wep4".equalsIgnoreCase(res.sectionId().trim())) {
                wep4MeshCount += res.meshes().size();
            }
        }
        assertTrue(wep4MeshCount > 0, "Trion DAT must contain wep4 meshes to verify exclusion");

        ActorAppearance trion = ActorAppearance.forStandardModel(64, 0, 27, 32);
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, trion);
        assertValidActor(loaded, "Trion");
        assertTrue(
                loaded.meshes().size() <= totalRawMeshes - wep4MeshCount,
                "Loaded Trion meshes (" + loaded.meshes().size() + ") must exclude all " + wep4MeshCount
                        + " wep4 meshes from total " + totalRawMeshes
        );
    }

    @Test
    @DisplayName("Retail: loadActor loads Chocobo (race 32)")
    void testLoadChocobo() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance chocobo = new ActorAppearance(
                ActorLookType.CHOCOBO,
                32, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 1, 0, 27, 32
        );
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, chocobo);
        assertValidActor(loaded, "Chocobo");
        assertTrue(loaded.slot1Animations().isEmpty(), "Chocobo must not load upper-body animation DAT");
        assertFalse(loaded.loadedModelIds().containsKey(ItemModelSlot.Face), "Chocobo must skip Face slot");
    }

    @Test
    @DisplayName("Retail: loadActor loads skeleton-less variant model 1977 inheriting base model 1976")
    void testLoadModel1977InheritsBase1976() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "Retail FFXI installation required at " + FFXI_ROOT);

        ActorAppearance model1977 = ActorAppearance.forStandardModel(1977, 0, 27, 32);
        LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, model1977);
        assertValidActor(loaded, "Model 1977");
        assertFalse(loaded.slot0Animations().isEmpty(), "Model 1977 must inherit animations from base model 1976");
    }

    private static void assertValidActor(LoadedCharacter loaded, String label) {
        assertNotNull(loaded, label + " must not be null");
        assertNotNull(loaded.skeleton(), label + " skeleton must not be null");
        assertFalse(loaded.skeleton().joints().isEmpty(), label + " skeleton must have joints");
        assertFalse(loaded.meshes().isEmpty(), label + " must have visible meshes");
        assertFalse(loaded.textures().isEmpty(), label + " must have textures");
        assertTrue(Float.isFinite(loaded.radius()) && loaded.radius() > 0.0f, label + " radius must be positive finite");
        assertTrue(Float.isFinite(loaded.feetOffsetY()) && loaded.feetOffsetY() >= 0.0f, label + " feetOffsetY must be finite");
        assertTrue(Float.isFinite(loaded.feetLocalY()), label + " feetLocalY must be finite");
    }

    private static void collectMeshes(ResourceNode node, List<SkeletonMeshResource> out) {
        if (node instanceof SkeletonMeshResource smr) {
            out.add(smr);
            return;
        }
        if (node instanceof DirectoryResource dir) {
            for (ResourceNode child : dir.children()) {
                collectMeshes(child, out);
            }
        }
    }
}
