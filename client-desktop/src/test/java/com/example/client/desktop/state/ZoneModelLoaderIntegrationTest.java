package com.example.client.desktop.state;

import com.example.asset.collision.CollisionTransform;
import com.example.asset.config.FfxiPathConfig;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.environment.ZoneEnvironmentTable;
import com.ffxi.viewer.environment.ZonePointLight;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.ZoneFrustumCuller;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
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

        // Zone 231 (Northern San d'Oria) authentic fountain opaqueness, moat floor, and S05 water effects
        LoadedZone northSan = ZoneModelLoader.loadZone(ffxiRoot, 231);
        assertNotNull(northSan);
        List<LoadedZone.ZoneChunkInstance> fountainChunks = northSan.chunkInstances().stream()
                .filter(c -> !c.isEffect() && (
                        c.chunk().textureName().toLowerCase().contains("san_funs")
                                || c.chunk().textureName().toLowerCase().contains("san_tetu")
                                || c.chunk().textureName().toLowerCase().contains("san_kadn")))
                .toList();
        assertFalse(fountainChunks.isEmpty(), "Zone 231 must load Victory Square fountain scenery chunks");
        assertTrue(fountainChunks.stream().noneMatch(c -> c.chunk().renderBlend()),
                "Northern San d'Oria fountain stonework (uwa_funsui) must remain fully opaque (renderBlend=false)");

        List<LoadedZone.ZoneChunkInstance> moatFloorChunks = northSan.chunkInstances().stream()
                .filter(c -> !c.isEffect()
                        && (c.chunk().textureName() == null || c.chunk().textureName().isBlank())
                        && c.chunk().vertices().stream().allMatch(v -> v.colorR() == 0 && v.colorG() == 0 && v.colorB() == 0))
                .toList();
        assertFalse(moatFloorChunks.isEmpty(), "Zone 231 must preserve untextured dark moat floor geometry");
        assertTrue(moatFloorChunks.stream().noneMatch(c -> c.chunk().renderBlend()),
                "Northern San d'Oria dark moat floor chunks must stay opaque instead of being misclassified as water");

        List<LoadedZone.ZoneChunkInstance> effectChunks = northSan.chunkInstances().stream()
                .filter(LoadedZone.ZoneChunkInstance::isEffect)
                .toList();
        assertTrue(effectChunks.size() >= 15,
                "Zone 231 must load at least 15 S05 ambient zone effect chunks, got " + effectChunks.size());

        boolean hasWt03 = effectChunks.stream().anyMatch(c -> {
            Vector3f t = c.modelMatrix().getTranslation(new Vector3f());
            return c.effectParams().generatorId().equalsIgnoreCase("wt03")
                    && Math.abs(t.x - 57.4f) < 1.0f && Math.abs(t.z - 113.1f) < 1.0f;
        });
        boolean hasWt04 = effectChunks.stream().anyMatch(c -> {
            Vector3f t = c.modelMatrix().getTranslation(new Vector3f());
            return c.effectParams().generatorId().equalsIgnoreCase("wt04")
                    && Math.abs(t.x + 33.3f) < 1.0f && Math.abs(t.z - 112.9f) < 1.0f;
        });
        boolean hasSui1 = effectChunks.stream().anyMatch(c -> {
            Vector3f t = c.modelMatrix().getTranslation(new Vector3f());
            return c.effectParams().generatorId().equalsIgnoreCase("sui1")
                    && Math.abs(t.x) < 1.0f && Math.abs(t.z - 40.0f) < 1.0f;
        });
        assertTrue(hasWt03 && hasWt04, "Zone 231 moat water sheets (wt03, wt04) must instantiate at S05 world coordinates");
        assertTrue(hasSui1, "Zone 231 fountain basin water (sui1) must instantiate at (0.0, -0.6, 40.0)");

        // Retail S2F diurnal environment table, S05 point lights, weather sky layers, streetlamp cones & floor metadata
        ZoneEnvironmentTable envTable231 = northSan.environmentTable();
        assertNotNull(envTable231, "Zone 231 must parse S2F_Environment table");
        assertFalse(envTable231.isEmpty(), "Zone 231 S2F_Environment table must be populated");
        var midnightEnv = envTable231.resolve("fine", 0.0f, null).orElseThrow();
        var dawnEnv = envTable231.resolve("fine", 360.0f, null).orElseThrow();
        var noonEnv = envTable231.resolve("fine", 720.0f, null).orElseThrow();
        var duskEnv = envTable231.resolve("fine", 1110.0f, null).orElseThrow();
        assertTrue(noonEnv.hasDome() && noonEnv.skyColors().length == 8 && noonEnv.skyStops().length == 8);
        assertTrue(noonEnv.bgAmbientColor().lengthSquared() > midnightEnv.bgAmbientColor().lengthSquared(),
                "Retail Zone 231 noon ambient must be brighter than midnight");
        assertTrue(duskEnv.bgLight0Color().x > duskEnv.bgLight0Color().z,
                "Retail Zone 231 dusk sunlight must have warm red/gold tint");
        assertTrue(dawnEnv.bgLight0Dir().x > 0.0f,
                "Dawn light vector toward sun points East (-sunTravel), i.e., +X direction in WorldScene");

        LoadedZone chateau = ZoneModelLoader.loadZone(ffxiRoot, 233);
        assertFalse(chateau.environmentTable().availableAreaIds().isEmpty(),
                "Zone 233 (Chateau d'Oraguille) must expose indoor area S2F overrides");
        String indoorArea233 = chateau.environmentTable().availableAreaIds().stream().sorted().findFirst().orElseThrow();
        var chateauEnv = chateau.environmentTable().resolve("fine", 720.0f, indoorArea233).orElseThrow();
        assertTrue(chateauEnv.indoor() || !chateauEnv.hasDome(),
                "Zone 233 indoor area '" + indoorArea233 + "' must resolve indoor/domeless S2F environment");

        List<ZonePointLight> pl231 = northSan.pointLights();
        assertTrue(pl231.size() >= 30, "Zone 231 must resolve at least 30 zone point lights, got " + pl231.size());
        boolean hasDiurnalLampLight = pl231.stream().anyMatch(pl ->
                pl.range() > 0.0f && pl.evaluateIntensity(0.5f) == 0.0f && pl.evaluateIntensity(0.0f) > 1.0f);
        assertTrue(hasDiurnalLampLight, "Zone 231 must include pttm-controlled nighttime streetlamps");

        assertFalse(northSan.weatherSkyEffectsByTag().isEmpty(), "Zone 231 must load weat/<tag>/ S05 sky layers");
        Vector4f liveRgba = new Vector4f();
        boolean hasDiurnalLampCone = effectChunks.stream().anyMatch(c -> {
            c.effectParams().evaluateLiveColor(0.5f, liveRgba);
            float noonAlpha = liveRgba.w;
            c.effectParams().evaluateLiveColor(0.0f, liveRgba);
            float midnightAlpha = liveRgba.w;
            return noonAlpha <= 0.02f && midnightAlpha > 0.05f;
        });
        assertTrue(hasDiurnalLampCone, "Zone 231 must load nighttime optm+spaf streetlamp cones off at noon and glowing at midnight");

        CollisionTransform floorTx = northSan.collisionSystem().findFloorTransform(0.0f, -0.5f, 36.0f);
        assertNotNull(floorTx, "Zone 231 floor collision transform must resolve in Victory Square");

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
