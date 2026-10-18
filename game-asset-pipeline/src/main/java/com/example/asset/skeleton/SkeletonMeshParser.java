package com.example.asset.skeleton;

import com.example.asset.geometry.Vector3f;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.reader.DatByteReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses FFXI S2A_SKELETON_MESH and S25_WEIGHTED_MESH sections into skinned 3D geometry.
 */
public final class SkeletonMeshParser {

    private SkeletonMeshParser() {
    }

    private static class RawVertex {
        Vector3f p0 = new Vector3f(0f, 0f, 0f);
        Vector3f p1 = new Vector3f(0f, 0f, 0f);
        Vector3f n0 = new Vector3f(0f, 1f, 0f);
        Vector3f n1 = new Vector3f(0f, 1f, 0f);
        int joint0 = 0;
        int joint1 = 0;
        float weight0 = 1.0f;
        float weight1 = 0.0f;
    }

    public static Optional<SkinnedMesh> parse(DatSectionHeader header, DatByteReader payload) {
        try {
            payload.position(0);

            int flags1 = payload.nextU8();
            int flags2 = payload.nextU8();
            int flags3 = payload.nextU8();
            boolean clothEffect = (flags3 & 0x01) != 0;
            boolean useJointArray = (flags3 & 0x80) != 0;
            boolean hasNormals = !clothEffect;

            int occludeType = payload.nextU8();
            int flags5 = payload.nextU8();
            boolean symmetric = (flags5 == 0x01);
            int flags6 = payload.nextU8();

            int instructionOffset = 2 * payload.nextI32();
            int maybeMeshCount = payload.nextU8();
            int maybeInstructionCount = payload.nextU8();

            int jointArrayOffset = 2 * payload.nextI32();
            int numJoints = payload.nextU16();

            int vertexCountsOffset = 2 * payload.nextI32();
            int numVertexCounts = payload.nextU16();

            int vertexJointMappingOffset = 2 * payload.nextI32();
            int vertexJointMappingCount = payload.nextU16();

            int vertexDataOffset = 2 * payload.nextI32();
            int maybeVertexDataSize = payload.nextU16();

            int endOffset = 2 * payload.nextI32();
            int endOffsetDataSize = payload.nextU16();

            // 1. Read joint indices array
            int[] joints = new int[numJoints];
            if (numJoints > 0 && jointArrayOffset > 0 && jointArrayOffset < payload.capacity()) {
                payload.position(jointArrayOffset);
                for (int i = 0; i < numJoints; i++) {
                    joints[i] = payload.nextU16();
                }
            }

            // 2. Read vertex counts (single-jointed vs double-jointed)
            int singleCount = 0;
            int doubleCount = 0;
            if (vertexCountsOffset > 0 && vertexCountsOffset < payload.capacity()) {
                payload.position(vertexCountsOffset);
                singleCount = payload.nextU16();
                doubleCount = payload.nextU16();
            }

            int totalRawVertices = singleCount + doubleCount;
            if (totalRawVertices == 0) {
                return Optional.of(new SkinnedMesh(header.sectionId(), List.of()));
            }

            RawVertex[] rawVerts = new RawVertex[totalRawVertices];
            for (int i = 0; i < totalRawVertices; i++) {
                rawVerts[i] = new RawVertex();
            }

            // 3. Read joint mappings
            if (vertexJointMappingOffset > 0 && vertexJointMappingOffset < payload.capacity()) {
                payload.position(vertexJointMappingOffset);
                for (int i = 0; i < singleCount; i++) {
                    int ref0 = payload.nextU16() & 0x7F;
                    int ref1 = payload.nextU16() & 0x7F;
                    rawVerts[i].joint0 = (useJointArray && ref0 < joints.length) ? joints[ref0] : ref0;
                    rawVerts[i].joint1 = 0;
                    rawVerts[i].weight0 = 1.0f;
                    rawVerts[i].weight1 = 0.0f;
                }
                for (int i = 0; i < doubleCount; i++) {
                    int idx = singleCount + i;
                    int ref0 = payload.nextU16() & 0x7F;
                    int ref1 = payload.nextU16() & 0x7F;
                    rawVerts[idx].joint0 = (useJointArray && ref0 < joints.length) ? joints[ref0] : ref0;
                    rawVerts[idx].joint1 = (useJointArray && ref1 < joints.length) ? joints[ref1] : ref1;
                }
            }

            // 4. Read positions, normals, and weights
            if (vertexDataOffset > 0 && vertexDataOffset < payload.capacity()) {
                payload.position(vertexDataOffset);
                for (int i = 0; i < singleCount; i++) {
                    rawVerts[i].p0 = payload.nextVector3f();
                    if (hasNormals) {
                        rawVerts[i].n0 = payload.nextVector3f();
                    }
                }
                for (int i = 0; i < doubleCount; i++) {
                    int idx = singleCount + i;
                    float p0x = payload.nextFloat();
                    float p1x = payload.nextFloat();
                    float p0y = payload.nextFloat();
                    float p1y = payload.nextFloat();
                    float p0z = payload.nextFloat();
                    float p1z = payload.nextFloat();
                    rawVerts[idx].p0 = new Vector3f(p0x, p0y, p0z);
                    rawVerts[idx].p1 = new Vector3f(p1x, p1y, p1z);

                    rawVerts[idx].weight0 = payload.nextFloat();
                    rawVerts[idx].weight1 = payload.nextFloat();

                    if (hasNormals) {
                        float n0x = payload.nextFloat();
                        float n1x = payload.nextFloat();
                        float n0y = payload.nextFloat();
                        float n1y = payload.nextFloat();
                        float n0z = payload.nextFloat();
                        float n1z = payload.nextFloat();
                        rawVerts[idx].n0 = new Vector3f(n0x, n0y, n0z);
                        rawVerts[idx].n1 = new Vector3f(n1x, n1y, n1z);
                    }
                }
            }

            // 5. Parse mesh instructions (TriMesh and TriStrip opcodes)
            List<SkinnedSubMesh> subMeshes = new ArrayList<>();
            if (instructionOffset > 0 && instructionOffset < payload.capacity()) {
                payload.position(instructionOffset);
                String currentTextureName = "";

                while (payload.remaining() >= 2) {
                    int opCode = payload.nextU16();
                    if (opCode == 0xFFFF) {
                        break;
                    } else if (opCode == 0x8000) {
                        currentTextureName = payload.nextString(16).trim();
                        String[] parts = currentTextureName.split("\\s+");
                        currentTextureName = parts[parts.length - 1].replaceAll("[^a-zA-Z0-9_-]", "").trim();
                    } else if (opCode == 0x8010) {
                        payload.skip(48); // Skip render properties
                    } else if (opCode == 0x0054) { // TriMesh
                        int numTriangles = payload.nextU16();
                        List<SkinnedVertex> verts = new ArrayList<>();
                        List<Integer> indices = new ArrayList<>();

                        for (int t = 0; t < numTriangles; t++) {
                            int v0 = payload.nextU16();
                            int v1 = payload.nextU16();
                            int v2 = payload.nextU16();

                            float u0 = payload.nextFloat();
                            float v0Coord = payload.nextFloat();
                            float u1 = payload.nextFloat();
                            float v1Coord = payload.nextFloat();
                            float u2 = payload.nextFloat();
                            float v2Coord = payload.nextFloat();

                            int base = verts.size();
                            verts.add(toSkinnedVertex(rawVerts[v0], u0, v0Coord));
                            verts.add(toSkinnedVertex(rawVerts[v1], u1, v1Coord));
                            verts.add(toSkinnedVertex(rawVerts[v2], u2, v2Coord));

                            indices.add(base);
                            indices.add(base + 1);
                            indices.add(base + 2);
                        }
                        subMeshes.add(new SkinnedSubMesh(currentTextureName, false, true, verts, indices));
                    } else if (opCode == 0x5453) { // TriStrip
                        int numTriangles = payload.nextU16();
                        int v0 = payload.nextU16();
                        int v1 = payload.nextU16();
                        int v2 = payload.nextU16();

                        float u0 = payload.nextFloat();
                        float v0Coord = payload.nextFloat();
                        float u1 = payload.nextFloat();
                        float v1Coord = payload.nextFloat();
                        float u2 = payload.nextFloat();
                        float v2Coord = payload.nextFloat();

                        List<SkinnedVertex> stripVerts = new ArrayList<>();
                        stripVerts.add(toSkinnedVertex(rawVerts[v0], u0, v0Coord));
                        stripVerts.add(toSkinnedVertex(rawVerts[v1], u1, v1Coord));
                        stripVerts.add(toSkinnedVertex(rawVerts[v2], u2, v2Coord));

                        for (int i = 1; i < numTriangles; i++) {
                            int vNext = payload.nextU16();
                            float uNext = payload.nextFloat();
                            float vNextCoord = payload.nextFloat();
                            stripVerts.add(toSkinnedVertex(rawVerts[vNext], uNext, vNextCoord));
                        }

                        List<Integer> stripIndices = new ArrayList<>();
                        for (int i = 0; i < stripVerts.size() - 2; i++) {
                            int idx0 = (i % 2 == 0) ? i : i + 1;
                            int idx1 = (i % 2 == 0) ? i + 1 : i;
                            int idx2 = i + 2;
                            stripIndices.add(idx0);
                            stripIndices.add(idx1);
                            stripIndices.add(idx2);
                        }
                        subMeshes.add(new SkinnedSubMesh(currentTextureName, false, true, stripVerts, stripIndices));
                    } else if (opCode == 0x0043) { // Untextured TriMesh
                        int numTriangles = payload.nextU16();
                        payload.skip(numTriangles * (3 * 2 + 4)); // 3 vertex indices + 4-byte BGRA
                    } else if (opCode == 0x4353) { // Untextured TriStrip
                        int numTriangles = payload.nextU16();
                        payload.skip(6 + 4 + (numTriangles - 1) * 2);
                    } else {
                        break;
                    }
                }
            }

            return Optional.of(new SkinnedMesh(header.sectionId(), subMeshes));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static SkinnedVertex toSkinnedVertex(RawVertex raw, float u, float v) {
        return new SkinnedVertex(
                raw.p0,
                raw.n0,
                u,
                v,
                raw.joint0,
                raw.joint1,
                raw.weight0,
                raw.weight1,
                128,
                128,
                128,
                255
        );
    }
}
