package com.example.client.desktop.state;

import com.example.asset.config.FfxiPathConfig;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.ZoneFrustumCuller;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ZoneModelLoaderIntegrationTest {

    @Test
    @DisplayName("Verify ZoneModelLoader and CharacterModelLoader load authentic assets from retail FFXI")
    void testLoadZoneAndCharacter() throws Exception {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        assumeTrue(Files.isDirectory(ffxiRoot), "FFXI retail install not found at " + ffxiRoot);

        // 1. Zone
        LoadedZone zone = ZoneModelLoader.loadZone(ffxiRoot, 230);
        assertNotNull(zone);
        System.out.printf("LoadedZone 230: %s, %d chunk instances, %d textures%n",
                zone.zoneName(), zone.chunkInstances().size(), zone.textureSources().size());
        assertFalse(zone.chunkInstances().isEmpty());
        assertNotNull(zone.spacePartitioningTreeRoot(), "Zone 230 must expose parsed spacePartitioningTreeRoot");
        BitSet indexed = ZoneFrustumCuller.collectIndexedObjectIds(zone.spacePartitioningTreeRoot());
        assertFalse(indexed.isEmpty(), "Zone 230 space partitioning tree must index zone object IDs");

        Matrix4f closeProjView = new Matrix4f()
                .perspective((float) Math.toRadians(50.0), 16.0f / 9.0f, 0.2f, 200.0f)
                .lookAt(new Vector3f(0f, -2f, 35f), new Vector3f(0f, -2f, 55f), new Vector3f(0f, -1f, 0f));
        BitSet visible = ZoneFrustumCuller.collectVisibleObjectIds(zone.spacePartitioningTreeRoot(), closeProjView);
        assertTrue(visible.cardinality() > 0 && visible.cardinality() < indexed.cardinality(),
                "Frustum query at gate should select a strict non-empty subset (" + visible.cardinality()
                        + " / " + indexed.cardinality() + ")");

        // Unified CollisionMap checks for Zone 230
        assertNotNull(zone.collisionSystem(), "Zone 230 must expose unified ZoneCollisionSystem");
        assertTrue(zone.collisionSystem().getAllWorldTriangles().size() >= 100_000,
                "Zone 230 expected at least 100,000 collision triangles");
        float aubejartFloor = zone.collisionSystem().findFloorY(2.900f, 46.644f, -4.0f);
        assertEquals(-1.999f, aubejartFloor, 0.05f, "Zone 230 Aubejart floor Y should match LSB pos_y");

        // Zone 100 (West Ronfaure) collision smoke check
        LoadedZone westRonfaure = ZoneModelLoader.loadZone(ffxiRoot, 100);
        assertNotNull(westRonfaure);
        assertNotNull(westRonfaure.collisionSystem(), "Zone 100 must expose unified ZoneCollisionSystem");
        assertNotNull(westRonfaure.collisionSystem().findAnyFloor(), "Zone 100 must locate walkable floor");

        // 2. Character
        LoadedCharacter character = CharacterModelLoader.loadCharacter(ffxiRoot, RaceGenderConfig.HumeM);
        assertNotNull(character);
        System.out.printf("LoadedCharacter: %d meshes, %d textures, %d slot0 anims, %d slot1 anims%n",
                character.meshes().size(), character.textures().size(),
                character.slot0Animations().size(), character.slot1Animations().size());
        assertFalse(character.meshes().isEmpty());
        try (AuthenticCharacterInstance charInstance = new AuthenticCharacterInstance(character, List.of(), null, false)) {
            float nameplateY = charInstance.resolveNameplateHeightOffset(2.0f);
            assertTrue(Float.isFinite(nameplateY) && nameplateY > 1.0f && nameplateY < 4.0f,
                    "Expected plausible retail nameplate offset for HumeM, got " + nameplateY);
        }
    }
}
