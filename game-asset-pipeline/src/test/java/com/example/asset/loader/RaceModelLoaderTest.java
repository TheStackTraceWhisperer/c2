package com.example.asset.loader;

import com.example.asset.geometry.Matrix4f;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.Skeleton;
import com.example.asset.skeleton.SkeletonAnimation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RaceModelLoaderTest {

    private static RaceModelLoader loader;

    @BeforeAll
    static void setUp() {
        RomAssetLoader romLoader = RomAssetLoader.getInstance();
        assertFalse(romLoader.isRemoteFetchEnabled(), "Remote fetch should be disabled when retail install directory exists");
        loader = new RaceModelLoader(romLoader);
    }

    @Test
    @DisplayName("Load authentic Hume Male character model with full equipment and textures")
    void testLoadHumeMaleModel() {
        CompositeCharacterModel model = loader.loadRaceModel(RaceModelLoader.CharacterRace.HUME_MALE);
        assertNotNull(model, "Model must not be null");

        Skeleton skeleton = model.skeleton();
        assertNotNull(skeleton, "Skeleton must be loaded from 82.DAT");
        assertTrue(skeleton.jointCount() > 20, "Hume skeleton should have at least 20 joints, found: " + skeleton.jointCount());

        // Verify equipment slots
        assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HEAD), "Must contain HEAD mesh");
        assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY), "Must contain BODY mesh");
        assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HANDS), "Must contain HANDS mesh");
        assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_LEGS), "Must contain LEGS mesh");
        assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_FEET), "Must contain FEET mesh");

        int totalVertices = model.equipmentSlots().values().stream().mapToInt(m -> m.totalVertexCount()).sum();
        int totalTriangles = model.equipmentSlots().values().stream().mapToInt(m -> m.totalTriangleCount()).sum();
        assertTrue(totalVertices > 100, "Authentic equipment meshes should have >100 vertices, found: " + totalVertices);
        assertTrue(totalTriangles > 50, "Authentic equipment meshes should have >50 triangles, found: " + totalTriangles);

        // Verify textures
        assertFalse(model.textures().isEmpty(), "Authentic character model should contain decoded textures");
        assertTrue(model.textures().containsKey("hm_b") || model.textures().containsKey("hm_h"),
                "Should contain authentic Hume Male textures hm_b or hm_h");

        // Verify animations
        Map<String, SkeletonAnimation> anims = loader.loadRaceAnimations(RaceModelLoader.CharacterRace.HUME_MALE);
        assertFalse(anims.isEmpty(), "Animations must not be empty");
        assertTrue(anims.containsKey("idl0"), "Should contain idle animation idl0");
        assertTrue(anims.containsKey("wlk0") || anims.containsKey("run0"), "Should contain locomotion animations");

        SkeletonAnimation idle = anims.get("idl0");
        assertNotNull(idle);
        List<Matrix4f> pose = model.computePoseMatrices(idle, 0.0f);
        assertEquals(skeleton.jointCount(), pose.size(), "Computed pose matrices count should match skeleton joint count");
    }

    @Test
    @DisplayName("Load authentic Elvaan Male and Mithra character models")
    void testLoadElvaanAndMithra() {
        CompositeCharacterModel elvaan = loader.loadRaceModel(RaceModelLoader.CharacterRace.ELVAAN_MALE);
        assertNotNull(elvaan.skeleton());
        assertTrue(elvaan.skeleton().jointCount() > 20);
        assertTrue(elvaan.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY));

        CompositeCharacterModel mithra = loader.loadRaceModel(RaceModelLoader.CharacterRace.MITHRA);
        assertNotNull(mithra.skeleton());
        assertTrue(mithra.skeleton().jointCount() > 20);
        assertTrue(mithra.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY));
    }

    @Test
    @DisplayName("Load authentic town NPC / Guard model")
    void testLoadNpcModel() {
        CompositeCharacterModel guard = loader.loadNpcModel(0, null);
        assertNotNull(guard);
        assertNotNull(guard.skeleton());
        assertTrue(guard.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY));
        assertTrue(guard.equipmentSlots().get(CompositeCharacterModel.SLOT_BODY).totalVertexCount() > 0);
    }
}
