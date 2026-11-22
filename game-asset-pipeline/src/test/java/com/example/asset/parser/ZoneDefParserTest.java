package com.example.asset.parser;

import com.example.asset.collision.CollisionMap;
import com.example.asset.config.FfxiPathConfig;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.Vector3f;
import com.example.asset.reader.DatByteReader;
import com.ffxi.crypto.KeyTables;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.io.FileUtil;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.ZoneDefResource;
import com.ffxi.parser.DatParser;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ZoneDefParserTest {

    /** South San d'Oria (zone 230) retail ROM file. */
    private static final String SOUTH_SAN_DORIA_ROM = "ROM/1/31.DAT";

    @Test
    @DisplayName("Should parse South San d'Oria (Zone 230) S1C_ZONE_DEF collision mesh and raycast floor (retail DATs required)")
    void testParseSouthSanDoriaCollisionMesh() throws Exception {
        Optional<Path> dat = FfxiPathConfig.resolveRomFile(SOUTH_SAN_DORIA_ROM);
        assumeTrue(dat.isPresent(), "Retail FFXI " + SOUTH_SAN_DORIA_ROM + " not found under " + FfxiPathConfig.resolveFfxiInstallDirectory());

        DatByteReader reader = new DatByteReader(Files.readAllBytes(dat.get()));

        Optional<CollisionMap> collisionMapOpt = ZoneDefParser.parseFromDat(reader);
        assertTrue(collisionMapOpt.isPresent(), "South San d'Oria must contain a S1C_ZONE_DEF collision map");

        CollisionMap map = collisionMapOpt.get();
        assertTrue(map.numBlocksWide() > 0);
        assertTrue(map.numBlocksLong() > 0);
        assertTrue(map.totalCellsX() > 0);
        assertTrue(map.totalCellsZ() > 0);

        List<CollisionTriangle> allTriangles = map.allWorldSpaceTriangles();
        assertFalse(allTriangles.isEmpty(), "Collision map must contain collision triangles");
        assertTrue(allTriangles.size() >= 100_000, "Expected at least 100,000 collision triangles for South San d'Oria");

        boolean foundGround = false;
        for (float x = -100f; x <= 100f && !foundGround; x += 20f) {
            for (float z = -100f; z <= 100f; z += 20f) {
                Optional<RayHit> hit = map.raycastFloor(new Vector3f(x, -100f, z), 50.0f, 500.0f, true);
                if (hit.isPresent()) {
                    foundGround = true;
                    break;
                }
            }
        }
        assertTrue(foundGround, "Raycast should find ground surface in South San d'Oria");

        // Verify exact native LSB NPC coordinates in Southern San d'Oria (ROM/1/31.DAT) hit floor within 0.05m
        record LsbNpcSample(String name, float x, float y, float z) {}
        List<LsbNpcSample> samples = List.of(
                new LsbNpcSample("Aubejart", 2.900f, -1.999f, 46.644f),
                new LsbNpcSample("Ceraule", -86.077f, 1.999f, -34.793f),
                new LsbNpcSample("Cahaurme", 55.749f, -8.601f, -29.354f),
                new LsbNpcSample("Lotte", 96.773f, 0.0f, 116.967f),
                new LsbNpcSample("Leuveret", -136.322f, -8.800f, 21.958f)
        );
        for (LsbNpcSample npc : samples) {
            Optional<RayHit> hit = map.raycastFloor(new Vector3f(npc.x(), npc.y(), npc.z()), 2.0f, 10.0f, true);
            assertTrue(hit.isPresent(), "Expected floor raycast hit for native LSB NPC " + npc.name());
            assertEquals(npc.y(), hit.get().point().y(), 0.05f,
                    "Floor Y for " + npc.name() + " should match native LSB pos_y within 0.05m");
        }
    }

    @Test
    @DisplayName("Should return Optional.empty() safely when DAT has no S1C_ZONE_DEF section")
    void testParseDatWithoutZoneDefReturnsEmpty() {
        // A single S00_END-style empty buffer / zero-length DAT has no zone def section.
        DatByteReader reader = new DatByteReader(new byte[0]);
        assertFalse(ZoneDefParser.parseFromDat(reader).isPresent(), "DAT without S1C_ZONE_DEF should return Optional.empty()");

        DatByteReader zeros = new DatByteReader(new byte[64]);
        assertFalse(ZoneDefParser.parseFromDat(zeros).isPresent());
    }

    @Test
    @DisplayName("ZoneDefParser and DatParser produce identical CollisionMap and raycasts for Zone #230 and Zone #100 (retail DATs required)")
    void testZoneDefParserAndDatParserRetailParityForZone230AndZone100() throws Exception {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        assumeTrue(Files.isDirectory(ffxiRoot), "Retail FFXI directory not found: " + ffxiRoot);

        File dllFile = FileUtil.resolveCaseInsensitive(ffxiRoot.toFile(), "FFXiMain.dll");
        assumeTrue(dllFile != null && dllFile.exists(), "FFXiMain.dll not found under " + ffxiRoot);

        KeyTables.loadFromDll(new ByteReader(Files.readAllBytes(dllFile.toPath()), dllFile.getName()));
        FileTableManager ftm = new FileTableManager();
        ftm.loadFromDirectory(ffxiRoot.toFile());

        for (int zoneId : new int[]{230, 100}) {
            String zonePath = ZoneIdToResourceId.getMainAreaResourcePath(new ZoneConfig(zoneId), ftm);
            assumeTrue(zonePath != null && Files.isRegularFile(Path.of(zonePath)),
                    "Missing retail DAT for zone #" + zoneId + ": " + zonePath);

            byte[] rawBytes = Files.readAllBytes(Path.of(zonePath));
            CollisionMap viaZoneDef = ZoneDefParser.parseFromDat(new DatByteReader(rawBytes.clone())).orElseThrow();
            DatResourceGraph graph = DatParser.parseResourceGraph(
                    new ByteReader(rawBytes, Path.of(zonePath).getFileName().toString()));
            CollisionMap viaDatParser = findCollisionMap(graph.rootDirectory());
            assertNotNull(viaDatParser, "DatParser must populate CollisionMap for zone #" + zoneId);

            assertEquals(viaZoneDef.numBlocksWide(), viaDatParser.numBlocksWide(), "numBlocksWide zone " + zoneId);
            assertEquals(viaZoneDef.numBlocksLong(), viaDatParser.numBlocksLong(), "numBlocksLong zone " + zoneId);
            assertEquals(viaZoneDef.blockWidth(), viaDatParser.blockWidth(), "blockWidth zone " + zoneId);
            assertEquals(viaZoneDef.blockLength(), viaDatParser.blockLength(), "blockLength zone " + zoneId);
            assertEquals(viaZoneDef.subBlocksX(), viaDatParser.subBlocksX(), "subBlocksX zone " + zoneId);
            assertEquals(viaZoneDef.subBlocksZ(), viaDatParser.subBlocksZ(), "subBlocksZ zone " + zoneId);
            assertEquals(viaZoneDef.totalCellsX(), viaDatParser.totalCellsX(), "totalCellsX zone " + zoneId);
            assertEquals(viaZoneDef.totalCellsZ(), viaDatParser.totalCellsZ(), "totalCellsZ zone " + zoneId);
            assertEquals(viaZoneDef.allObjects().size(), viaDatParser.allObjects().size(), "allObjects size zone " + zoneId);
            assertEquals(viaZoneDef.allWorldSpaceTriangles().size(), viaDatParser.allWorldSpaceTriangles().size(),
                    "allWorldSpaceTriangles size zone " + zoneId);
            assertTrue(viaZoneDef.allObjects().size() > 0 && viaZoneDef.allWorldSpaceTriangles().size() > 0);

            int floorHits = 0;
            for (int bz = 0; bz < viaZoneDef.totalCellsZ(); bz += 4) {
                for (int bx = 0; bx < viaZoneDef.totalCellsX(); bx += 4) {
                    float[] center = viaZoneDef.getBlockCenterXZ(bx, bz);
                    assertNotNull(center);
                    Vector3f probe = new Vector3f(center[0], -2000.0f, center[1]);
                    Optional<RayHit> hitA = viaZoneDef.raycastFloor(probe, 0.0f, Float.POSITIVE_INFINITY, true);
                    Optional<RayHit> hitB = viaDatParser.raycastFloor(probe, 0.0f, Float.POSITIVE_INFINITY, true);
                    assertEquals(hitA.isPresent(), hitB.isPresent(),
                            "Floor hit presence mismatch at zone " + zoneId + " cell (" + bx + "," + bz + ")");
                    if (hitA.isPresent()) {
                        floorHits++;
                        assertEquals(hitA.get().point().y(), hitB.get().point().y(), 1e-4f,
                                "Floor Y mismatch at zone " + zoneId + " cell (" + bx + "," + bz + ")");
                        // Also check segment parity horizontally through this floor elevation
                        float y = hitA.get().point().y() - 0.8f;
                        Vector3f s0 = new Vector3f(center[0] - 8.0f, y, center[1]);
                        Vector3f s1 = new Vector3f(center[0] + 8.0f, y, center[1]);
                        Optional<RayHit> segA = viaZoneDef.raycastSegment(s0, s1, true);
                        Optional<RayHit> segB = viaDatParser.raycastSegment(s0, s1, true);
                        assertEquals(segA.isPresent(), segB.isPresent(),
                                "Segment hit mismatch at zone " + zoneId + " cell (" + bx + "," + bz + ")");
                        if (segA.isPresent()) {
                            assertEquals(segA.get().distance(), segB.get().distance(), 1e-4f);
                        }
                    }
                }
            }
            assertTrue(floorHits >= 20, "Expected at least 20 sampled floor hits in zone #" + zoneId + ", got " + floorHits);
        }
    }

    private static CollisionMap findCollisionMap(DirectoryResource dir) {
        for (ResourceNode child : dir.children()) {
            if (child instanceof ZoneDefResource zdef && zdef.collisionMap() != null) {
                return zdef.collisionMap();
            }
            if (child instanceof DirectoryResource sub) {
                CollisionMap nested = findCollisionMap(sub);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
