package com.example.asset.parser;

import com.example.asset.collision.CollisionMap;
import com.example.asset.export.GltfWriter;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.Vector3f;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ZoneDefParserTest {

    @Test
    @DisplayName("Should parse South San d'Oria (Zone 230) S1C_ZONE_DEF collision mesh, raycast floor, and export .glb")
    void testParseSouthSanDoriaCollisionMeshAndExportGlb() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/dats/zones/south_san_doria_230.DAT");
        assumeTrue(stream != null, "Fixture south_san_doria_230.DAT is missing, skipping test.");

        byte[] datBytes = stream.readAllBytes();
        DatByteReader reader = new DatByteReader(datBytes);

        Optional<CollisionMap> collisionMapOpt = ZoneDefParser.parseFromDat(reader);
        assertTrue(collisionMapOpt.isPresent(), "South San d'Oria must contain a S1C_ZONE_DEF collision map");

        CollisionMap map = collisionMapOpt.get();
        assertTrue(map.numBlocksWide() > 0);
        assertTrue(map.numBlocksLong() > 0);
        assertTrue(map.totalCellsX() > 0);
        assertTrue(map.totalCellsZ() > 0);

        List<CollisionTriangle> allTriangles = map.allWorldSpaceTriangles();
        assertFalse(allTriangles.isEmpty(), "Collision map must contain collision triangles");
        System.out.printf("South San d'Oria total collision triangles: %d%n", allTriangles.size());
        assertTrue(allTriangles.size() >= 100_000, "Expected at least 100,000 collision triangles for South San d'Oria");

        // Verify broadphase raycast floor height detection
        boolean foundGround = false;
        for (float x = -50f; x <= 50f; x += 10f) {
            for (float z = -50f; z <= 50f; z += 10f) {
                Optional<RayHit> hit = map.raycastFloor(new Vector3f(x, 10f, z), 2.0f, 50.0f, true);
                if (hit.isPresent()) {
                    foundGround = true;
                    System.out.printf("Ground hit found at (%.1f, %.1f) -> height y = %.3f (distance = %.3f)%n",
                            x, z, hit.get().point().y(), hit.get().distance());
                    break;
                }
            }
            if (foundGround) {
                break;
            }
        }
        assertTrue(foundGround, "Raycast should find ground surface in South San d'Oria");

        // Export to binary glTF (.glb) for visual verification in Blender or 3D viewer
        Path glbPath = Paths.get("target", "south_san_doria_collision.glb");
        GltfWriter.writeGlb(allTriangles, glbPath);

        assertTrue(Files.exists(glbPath));
        long fileSize = Files.size(glbPath);
        System.out.printf("Exported %s (%d bytes)%n", glbPath, fileSize);
        assertTrue(fileSize > 1_000_000, "GLB collision export should be at least 1 MB");
    }

    @Test
    @DisplayName("Should return Optional.empty() safely when DAT has no S1C_ZONE_DEF section")
    void testParseDatWithoutZoneDefReturnsEmpty() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/dats/zones/west_ronfaure_100.DAT");
        assumeTrue(stream != null, "Fixture west_ronfaure_100.DAT is missing, skipping test.");

        byte[] datBytes = stream.readAllBytes();
        DatByteReader reader = new DatByteReader(datBytes);

        Optional<CollisionMap> collisionMapOpt = ZoneDefParser.parseFromDat(reader);
        assertFalse(collisionMapOpt.isPresent(), "DAT without S1C_ZONE_DEF should return Optional.empty()");
    }
}
