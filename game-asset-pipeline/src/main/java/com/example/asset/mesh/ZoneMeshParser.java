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

            // Convert FFXI coordinates (-x, -y, z) to glTF / OpenGL standard Y-up
            float px = ZoneMeshMath.sanitizeFinite(-p0.x());
            float py = ZoneMeshMath.sanitizeFinite(-p0.y());
            float pz = ZoneMeshMath.sanitizeFinite(p0.z());
            float nx = ZoneMeshMath.sanitizeFinite(-n0.x());
            float ny = ZoneMeshMath.sanitizeFinite(-n0.y());
            float nz = ZoneMeshMath.sanitizeFinite(n0.z());

            verts.add(new ZoneMeshVertex(new Vector3f(px, py, pz), new Vector3f(nx, ny, nz), u, v, r, g, b, a));
        }

        int numIndices = reader.nextU16();
        int unk1 = reader.nextU16();

        List<Integer> rawIndices = new ArrayList<>(numIndices);
        for (int i = 0; i < numIndices; i++) {
            rawIndices.add(reader.nextU16());
        }

        boolean isTriStrip = (meshTypeFlag == 1);
        List<Integer> triangleIndices = ZoneMeshMath.expandTriangles(isTriStrip, rawIndices);

        return new VisualSubMesh(textureName, blendEnabled, backFaceCulling, verts, triangleIndices);
    }
}
