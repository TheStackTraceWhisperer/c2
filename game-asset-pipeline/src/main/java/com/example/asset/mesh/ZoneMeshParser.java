package com.example.asset.mesh;

import com.example.asset.decoder.ZoneDecrypt;
import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.Vector3f;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.reader.DatByteReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses FFXI S2E_ZONE_MESH sections into textured, renderable 3D visual geometry.
 */
public final class ZoneMeshParser {

    private ZoneMeshParser() {
    }

    /**
     * Parses a single S2E_ZONE_MESH section from a DAT byte reader.
     * Decrypts the payload in-place and decodes submeshes, vertices, UVs, and indices.
     */
    public static Optional<VisualZoneMesh> parseZoneMesh(DatSectionHeader header, DatByteReader reader) {
        try {
            ZoneDecrypt.decryptZoneMesh(header, reader);
            reader.position(header.dataStartPosition());

            int decryptInfo = reader.nextI32();
            int keyConfigData = reader.nextI32();
            String author = reader.nextString(8).trim();

            int configData = keyConfigData & 0xFF;
            int meshTypeFlag = configData & 1; // 0 = TriMesh, 1 = TriStrip
            boolean vertexBlendEnabled = (configData & 2) != 0;

            String meshName = reader.nextString(16).trim();
            int defStart = reader.position();

            // Section 1
            int meshCount0 = reader.nextI32();
            BoundingBox box0 = BoundingBox.read(reader);
            int section1DataStart = defStart + reader.nextI32();

            if (meshCount0 == 0) {
                return Optional.of(new VisualZoneMesh(header.sectionId(), meshName, author, box0, List.of()));
            }

            // Section 2
            int meshCount1 = reader.nextI32();
            BoundingBox box1 = BoundingBox.read(reader);
            int unknown = reader.nextI32();

            reader.position(section1DataStart);
            List<VisualSubMesh> subMeshes = new ArrayList<>(meshCount1);

            for (int m = 0; m < meshCount1; m++) {
                VisualSubMesh subMesh = parseSubMesh(reader, meshTypeFlag, vertexBlendEnabled);
                subMeshes.add(subMesh);
                reader.align(4);
            }

            return Optional.of(new VisualZoneMesh(header.sectionId(), meshName, author, box1 != null ? box1 : box0, subMeshes));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static VisualSubMesh parseSubMesh(DatByteReader reader, int meshTypeFlag, boolean vertexBlendEnabled) {
        String textureName = reader.nextString(16).trim();
        int numVerts = reader.nextU16();
        int flags = reader.nextU16();

        boolean blendEnabled = (flags & 0x8000) != 0;
        boolean backFaceCulling = (flags & 0x2000) == 0;

        List<ZoneMeshVertex> verts = new ArrayList<>(numVerts);
        for (int i = 0; i < numVerts; i++) {
            Vector3f p0 = reader.nextVector3f();
            if (vertexBlendEnabled) {
                reader.nextVector3f(); // p1
            }
            Vector3f n0 = reader.nextVector3f();
            int b = reader.nextU8();
            int g = reader.nextU8();
            int r = reader.nextU8();
            int a = reader.nextU8();
            float u = reader.nextFloat();
            float v = reader.nextFloat();

            verts.add(new ZoneMeshVertex(p0, n0, u, v, r, g, b, a));
        }

        int numIndices = reader.nextU16();
        int unk1 = reader.nextU16();

        int[] rawIndices = new int[numIndices];
        for (int i = 0; i < numIndices; i++) {
            rawIndices[i] = reader.nextU16();
        }

        List<Integer> triangleIndices = new ArrayList<>();
        if (meshTypeFlag == 0) {
            // Standard Triangle List
            for (int i = 0; i + 2 < numIndices; i += 3) {
                triangleIndices.add(rawIndices[i]);
                triangleIndices.add(rawIndices[i + 1]);
                triangleIndices.add(rawIndices[i + 2]);
            }
        } else {
            // Triangle Strip conversion to Triangle List
            for (int i = 0; i < numIndices - 2; i++) {
                int idx0 = (i % 2 == 0) ? rawIndices[i] : rawIndices[i + 1];
                int idx1 = (i % 2 == 0) ? rawIndices[i + 1] : rawIndices[i];
                int idx2 = rawIndices[i + 2];

                // Skip degenerate strip vertices
                if (idx0 != idx1 && idx1 != idx2 && idx0 != idx2) {
                    triangleIndices.add(idx0);
                    triangleIndices.add(idx1);
                    triangleIndices.add(idx2);
                }
            }
        }

        return new VisualSubMesh(textureName, blendEnabled, backFaceCulling, verts, triangleIndices);
    }
}
