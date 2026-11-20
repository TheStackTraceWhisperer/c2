package com.example.asset.parser;

import com.example.asset.collision.CollisionMap;
import com.example.asset.config.FfxiPathConfig;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.Vector3f;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
