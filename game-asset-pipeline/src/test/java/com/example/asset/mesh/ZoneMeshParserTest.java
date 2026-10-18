package com.example.asset.mesh;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.parser.DatSectionScanner;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ZoneMeshParserTest {

    @Test
    @DisplayName("Parse and verify S2E visual zone meshes from South San d'Oria DAT")
    void testParseZoneMeshes() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/dats/zones/south_san_doria_230.DAT");
        assumeTrue(stream != null, "Fixture south_san_doria_230.DAT is missing");

        byte[] datBytes = stream.readAllBytes();
        DatByteReader reader = new DatByteReader(datBytes);
        List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);

        List<DatSectionHeader> meshSections = sections.stream()
                .filter(s -> s.sectionType() == SectionType.S2E_ZONE_MESH)
                .toList();

        assertFalse(meshSections.isEmpty(), "South San d'Oria should contain S2E_ZONE_MESH sections");
        System.out.printf("Found %d S2E_ZONE_MESH sections in South San d'Oria%n", meshSections.size());

        int parsedCount = 0;
        int totalSubMeshes = 0;
        int totalTriangles = 0;
        int totalVertices = 0;

        for (DatSectionHeader header : meshSections) {
            Optional<VisualZoneMesh> meshOpt = ZoneMeshParser.parseZoneMesh(header, reader);
            if (meshOpt.isPresent()) {
                VisualZoneMesh mesh = meshOpt.get();
                assertNotNull(mesh.name());
                assertNotNull(mesh.boundingBox());
                parsedCount++;

                for (VisualSubMesh subMesh : mesh.subMeshes()) {
                    totalSubMeshes++;
                    totalVertices += subMesh.vertices().size();
                    totalTriangles += subMesh.triangleCount();

                    // Validate vertex properties
                    for (ZoneMeshVertex v : subMesh.vertices()) {
                        assertNotNull(v.position());
                        assertNotNull(v.normal());
                    }

                    // Validate index bounds
                    int vertexCount = subMesh.vertices().size();
                    for (int idx : subMesh.indices()) {
                        assertTrue(idx >= 0 && idx < vertexCount,
                                "Triangle index " + idx + " out of bounds for submesh with " + vertexCount + " vertices");
                    }
                }
            }
        }

        System.out.printf("Parsed %d/%d visual meshes, %d submeshes, %d vertices, %d triangles%n",
                parsedCount, meshSections.size(), totalSubMeshes, totalVertices, totalTriangles);

        assertTrue(parsedCount >= 200, "Expected at least 200 visual zone meshes to be successfully parsed");
        assertTrue(totalSubMeshes > 0, "Expected submeshes to be present");
        assertTrue(totalVertices > 10000, "Expected tens of thousands of visual vertices in South San d'Oria");
        assertTrue(totalTriangles > 5000, "Expected thousands of visual triangles in South San d'Oria");
    }
}
