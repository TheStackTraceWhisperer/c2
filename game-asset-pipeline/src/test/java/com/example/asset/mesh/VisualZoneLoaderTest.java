package com.example.asset.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VisualZoneLoaderTest {

    @Test
    @DisplayName("Load South San d'Oria DAT into VisualZoneScene")
    void testLoadVisualZoneScene() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/dats/zones/south_san_doria_230.DAT");
        assumeTrue(stream != null, "Fixture south_san_doria_230.DAT is missing");

        VisualZoneScene scene = VisualZoneLoader.load(stream);
        assertNotNull(scene);
        assertFalse(scene.textures().isEmpty(), "Expected textures to be parsed");
        assertFalse(scene.meshes().isEmpty(), "Expected meshes to be parsed");

        System.out.printf("VisualZoneScene loaded: %d textures, %d meshes, %d submeshes, %d vertices, %d triangles, %d zoneObjects%n",
                scene.textures().size(),
                scene.totalMeshCount(),
                scene.totalSubMeshCount(),
                scene.totalVertexCount(),
                scene.totalTriangleCount(),
                scene.zoneObjects().size());
        int matchedCount = 0;
        int unmatchedCount = 0;
        for (com.example.asset.mesh.ZoneObject obj : scene.zoneObjects()) {
            if (scene.getMesh(obj.meshName()).isPresent()) {
                matchedCount++;
            } else {
                unmatchedCount++;
            }
        }
        System.out.printf("ZoneObject matching: %d matched, %d unmatched%n", matchedCount, unmatchedCount);

        assertTrue(scene.textures().size() >= 40);
        assertTrue(scene.totalMeshCount() >= 200);
        assertTrue(scene.totalVertexCount() > 90000);
        assertTrue(scene.totalTriangleCount() > 60000);

        // Check finding a texture
        assertTrue(scene.getTexture("arch").isPresent() || scene.getTexture("door0").isPresent());
    }
}
