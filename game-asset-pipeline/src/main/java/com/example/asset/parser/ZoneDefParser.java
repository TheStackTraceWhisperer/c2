package com.example.asset.parser;

import com.example.asset.collision.CollisionMap;
import com.example.asset.collision.CollisionMesh;
import com.example.asset.collision.CollisionObject;
import com.example.asset.collision.CollisionObjectGroup;
import com.example.asset.collision.CollisionTransform;
import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.Matrix4f;
import com.example.asset.geometry.TerrainType;
import com.example.asset.geometry.TriFlags;
import com.example.asset.geometry.Vector3f;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.reader.DatByteReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses S1C_ZONE_DEF sections from FFXI zone DAT files, extracting 3D collision meshes,
 * transform instances, and the 2D spatial collision grid.
 */
public final class ZoneDefParser {

    private ZoneDefParser() {
    }

    /**
     * Scans the DAT buffer for the S1C_ZONE_DEF section and parses its collision geometry.
     */
    public static Optional<CollisionMap> parseFromDat(DatByteReader reader) {
        reader.position(0);
        List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);
        for (DatSectionHeader header : sections) {
            if (header.sectionType() == SectionType.S1C_ZONE_DEF) {
                return Optional.ofNullable(parse(reader, header));
            }
        }
        return Optional.empty();
    }

    /**
     * Parses collision geometry from a specific S1C_ZONE_DEF section header.
     */
    public static CollisionMap parse(DatByteReader reader, DatSectionHeader header) {
        if (header.sectionType() != SectionType.S1C_ZONE_DEF) {
            throw new IllegalArgumentException("Expected S1C_ZONE_DEF, but got " + header.sectionType());
        }

        com.example.asset.decoder.ZoneDecrypt.decryptZoneObjects(header, reader);

        reader.position(header.dataStartPosition());

        // Header section
        int decryptInfo = reader.nextI32();
        int keyNodeData = reader.nextI32();
        int nodeCount = keyNodeData & 0x00FFFFFF;

        int collisionMeshOffset = reader.nextI32();
        if (collisionMeshOffset == 0) {
            return null; // Zones without collision mesh (e.g. ship hulls)
        }

        int zoneBlocksX = reader.nextU8();
        int zoneBlocksZ = reader.nextU8();

        int blockWidth = reader.nextU8();
        int blockLength = reader.nextU8();

        int subBlocksX = blockWidth / 4;
        int subBlocksZ = blockLength / 4;

        int spacePartitioningTreeOffset = reader.nextI32();
        int cullingTablesOffset = reader.nextI32();
        int pointLightOffset = reader.nextI32();
        int unk = reader.nextI32();

        // Navigate to collision mesh section
        reader.position(collisionMeshOffset + header.dataStartPosition());

        int numMeshes = reader.nextI32();
        int firstMeshOffset = reader.nextI32() + header.dataStartPosition();

        int matrixMeshPairCount = reader.nextI32();
        int matrixMeshPairsOffset = reader.nextI32() + header.dataStartPosition();

        int collisionMapOffset = reader.nextI32() + header.dataStartPosition();
        int collisionMeshTransformsOffset = reader.nextI32() + header.dataStartPosition();

        reader.nextI32(); // space tree index count match
        reader.nextI32(); // 0

        // Parse mesh transform pairs
        reader.position(matrixMeshPairsOffset);

        Map<Integer, CollisionTransform> transformsByOffset = new HashMap<>();
        Map<Integer, CollisionMesh> meshesByOffset = new HashMap<>();
        List<CollisionObjectGroup> collisionGroups = new ArrayList<>(matrixMeshPairCount);
        Map<Integer, Integer> collisionGroupsByIndex = new HashMap<>();

        for (int i = 0; i < matrixMeshPairCount; i++) {
            int groupPos = reader.position();
            collisionGroupsByIndex.put(groupPos, i);

            int countFlags = reader.nextI32();
            int groupSize = countFlags & 0x7FF;

            List<CollisionObject> groupObjects = new ArrayList<>(groupSize);

            for (int j = 0; j < groupSize; j++) {
                int objPos = reader.position();

                int matrixOffset = reader.nextI32() + header.dataStartPosition();
                int meshOffset = reader.nextI32() + header.dataStartPosition();

                int resumePos = reader.position();

                CollisionTransform transform = transformsByOffset.computeIfAbsent(matrixOffset, off -> {
                    reader.position(off);
                    return parseTransform(reader, off);
                });

                CollisionMesh mesh = meshesByOffset.computeIfAbsent(meshOffset, off -> {
                    reader.position(off);
                    return parseCollisionMesh(reader, off, header.dataStartPosition());
                });

                reader.position(resumePos);

                groupObjects.add(new CollisionObject(objPos, mesh, transform));
            }

            reader.nextI32(); // padding zero
            collisionGroups.add(new CollisionObjectGroup(groupPos, groupObjects));
        }

        // Parse 2D collision map grid
        reader.position(collisionMapOffset);

        int totalRows = zoneBlocksZ * subBlocksZ;
        int totalCols = zoneBlocksX * subBlocksX;
        CollisionObjectGroup[][] map = new CollisionObjectGroup[totalRows][totalCols];
        int found = 0;

        for (int z = 0; z < totalRows; z++) {
            for (int x = 0; x < totalCols; x++) {
                int offset = reader.nextI32();
                if (offset == 0) {
                    continue;
                }

                found++;
                int fileOffset = offset + header.dataStartPosition();
                Integer groupIdx = collisionGroupsByIndex.get(fileOffset);
                if (groupIdx != null) {
                    map[z][x] = collisionGroups.get(groupIdx);
                }

                if (found == matrixMeshPairCount) {
                    break;
                }
            }
            if (found == matrixMeshPairCount) {
                break;
            }
        }

        return new CollisionMap(zoneBlocksX, zoneBlocksZ, blockWidth, blockLength, subBlocksX, subBlocksZ, map);
    }

    private static CollisionTransform parseTransform(DatByteReader reader, int offset) {
        Matrix4f toWorldSpace = new Matrix4f();
        for (int i = 0; i < 16; i++) {
            toWorldSpace.set(i, reader.nextFloat());
        }

        Matrix4f toCollisionSpace = new Matrix4f();
        for (int i = 0; i < 16; i++) {
            toCollisionSpace.set(i, reader.nextFloat());
        }

        reader.nextVector3f();
        reader.nextVector3f();
        reader.nextVector3f();

        int miscFlags = reader.nextI32();
        int cullingGroupOffset = reader.nextI32();
        reader.nextI32(); // 4 light indices bytes

        int envId = reader.nextI32();
        float unkFloat0 = reader.nextFloat();
        float unkFloat1 = reader.nextFloat();

        int rawSubAreaLinkId = reader.nextI32();
        Integer subAreaLinkId = (rawSubAreaLinkId != 0) ? rawSubAreaLinkId : null;

        int mapId = 0x8 * ((miscFlags >>> 26) & 0x3) + ((miscFlags >>> 3) & 0x7);

        return new CollisionTransform(offset, toWorldSpace, toCollisionSpace, mapId, miscFlags, subAreaLinkId);
    }

    private static CollisionMesh parseCollisionMesh(DatByteReader reader, int offset, int dataStartPosition) {
        int positionOffset = reader.nextI32() + dataStartPosition;
        int directionOffset = reader.nextI32() + dataStartPosition;
        int indexOffset = reader.nextI32() + dataStartPosition;

        int numTris = reader.nextU16();
        int unk = reader.nextU16();

        List<CollisionTriangle> triangles = new ArrayList<>(numTris);
        BoundingBox.Builder bboxBuilder = BoundingBox.builder();

        reader.position(indexOffset);
        for (int i = 0; i < numTris; i++) {
            int rawP0 = reader.nextU16();
            int pOff0 = positionOffset + (rawP0 & 0x7FFF) * 12;

            int rawP1 = reader.nextU16();
            int pOff1 = positionOffset + (rawP1 & 0x3FFF) * 12;

            int rawP2 = reader.nextU16();
            int pOff2 = positionOffset + (rawP2 & 0x3FFF) * 12;

            int rawD = reader.nextU16();
            int dOff = directionOffset + (rawD & 0x7FFF) * 12;

            int nextIndexPos = reader.position();

            reader.position(pOff0);
            Vector3f p0 = reader.nextVector3f();

            reader.position(pOff1);
            Vector3f p1 = reader.nextVector3f();

            reader.position(pOff2);
            Vector3f p2 = reader.nextVector3f();

            reader.position(dOff);
            Vector3f normal = reader.nextVector3f();

            int f0 = rawP0 >>> 12;
            int f1 = rawP1 >>> 12;
            int f2 = rawP2 >>> 12;
            int f3 = rawD >>> 12;

            int material = (f0 << 12) | (f1 << 8) | (f2 << 4) | f3;
            TerrainType terrainType = TerrainType.fromFlags(f0, f1, f2, f3);

            bboxBuilder.include(p0);
            bboxBuilder.include(p1);
            bboxBuilder.include(p2);

            triangles.add(new CollisionTriangle(p0, p1, p2, normal, new TriFlags(material), terrainType));

            reader.position(nextIndexPos);
        }

        // Sort triangles by descending abs(normal.y) to ease collision resolution with stairs (matching xim)
        triangles.sort((a, b) -> Float.compare(Math.abs(b.normal().y()), Math.abs(a.normal().y())));

        return new CollisionMesh(offset, triangles, bboxBuilder.build());
    }
}
