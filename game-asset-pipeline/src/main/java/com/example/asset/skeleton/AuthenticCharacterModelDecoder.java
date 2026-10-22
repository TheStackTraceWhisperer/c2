package com.example.asset.skeleton;

import com.example.asset.geometry.Vector3f;
import com.example.asset.texture.DecodedTexture;
import com.example.asset.texture.TextureParser;
import com.example.asset.reader.DatByteReader;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authentic FFXI character and NPC 3D model decoder.
 * Directly decodes retail DAT Section 0x2A (Model) and Section 0x29 (Skeleton) payloads,
 * replicating the exact bone assignment, dual-bone weighting, vertex transformation,
 * and mirrored geometry assembly from ffxi-extractor and XIM.
 */
@Slf4j
public final class AuthenticCharacterModelDecoder {

    private AuthenticCharacterModelDecoder() {
    }

    /**
     * Decodes all bone world matrices (hierarchical forward kinematics) from a retail 0x29 BONE chunk.
     */
    public static List<float[]> decodeSkeletonMatrices(byte[] datBytes) {
        if (datBytes == null || datBytes.length < 32) {
            return Collections.emptyList();
        }

        ByteBuffer buffer = ByteBuffer.wrap(datBytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;
        while (offset < datBytes.length - 8) {
            int packed = buffer.getInt(offset + 4);
            int type = packed & 0x7F;
            int blockSize = ((packed >> 7) & 0x7FFFF) * 16;

            if (type == 0x29) { // BONE chunk
                int dataStart = offset + 16;
                if (dataStart + 4 > datBytes.length) break;
                buffer.position(dataStart + 2);
                int noBone = buffer.getShort() & 0xFFFF;
                if (noBone <= 0 || noBone > 256) break;

                List<float[]> matrices = new ArrayList<>(noBone);
                for (int i = 0; i < noBone; i++) {
                    int boneOffset = dataStart + 4 + i * 30;
                    if (boneOffset + 30 > datBytes.length) break;
                    buffer.position(boneOffset);

                    int parent = buffer.get() & 0xFF;
                    if (i == 0) {
                        parent = 255;
                    }
                    buffer.get(); // skip term

                    float qi = buffer.getFloat();
                    float qj = buffer.getFloat();
                    float qk = buffer.getFloat();
                    float qw = buffer.getFloat();
                    float tx = buffer.getFloat();
                    float ty = buffer.getFloat();
                    float tz = buffer.getFloat();

                    float[] local = quatToMatrix(qi, qj, qk, qw, tx, ty, tz);
                    if (parent == 255 || parent >= matrices.size()) {
                        matrices.add(local);
                    } else {
                        matrices.add(mat4Multiply(local, matrices.get(parent)));
                    }
                }
                return matrices;
            }

            if (blockSize <= 0) break;
            offset += blockSize;
        }
        return Collections.emptyList();
    }

    /**
     * Decodes all 0x2A model chunks and 0x20 textures from a retail DAT file into a SkinnedMesh.
     */
    public static SkinnedMesh decodeModelChunk(byte[] datBytes, List<float[]> skelMatrices, String sectionId) {
        if (datBytes == null || datBytes.length < 52) {
            return new SkinnedMesh(sectionId, Collections.emptyList());
        }

        ByteBuffer buffer = ByteBuffer.wrap(datBytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;
        List<SkinnedSubMesh> subMeshes = new ArrayList<>();

        while (offset < datBytes.length - 8) {
            int packed = buffer.getInt(offset + 4);
            int type = packed & 0x7F;
            int blockSize = ((packed >> 7) & 0x7FFFF) * 16;

            if (type == 0x2A) { // Model Chunk
                int dataStart = offset + 16;
                int dataEnd = offset + blockSize;
                if (dataEnd <= datBytes.length && dataEnd - dataStart >= 52) {
                    byte[] chunkData = new byte[dataEnd - dataStart];
                    System.arraycopy(datBytes, dataStart, chunkData, 0, chunkData.length);
                    subMeshes.addAll(parseModelData(chunkData, skelMatrices));
                }
            }

            if (blockSize <= 0) break;
            offset += blockSize;
        }

        return new SkinnedMesh(sectionId, subMeshes);
    }

    public static String normalizeTextureName(String rawName) {
        if (rawName == null) return "";
        String s = rawName.trim().toLowerCase();
        if (s.startsWith("tim") || s.startsWith("txd")) {
            s = s.substring(3).trim();
        }
        String[] parts = s.split("\\s+");
        return parts[parts.length - 1].replaceAll("[^a-zA-Z0-9_-]", "").trim();
    }

    /**
     * Decodes all 0x20 texture chunks from a retail DAT file into a name -> DecodedTexture map.
     */
    public static Map<String, DecodedTexture> decodeTextures(byte[] datBytes) {
        if (datBytes == null || datBytes.length < 32) {
            return Collections.emptyMap();
        }

        ByteBuffer buffer = ByteBuffer.wrap(datBytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;
        Map<String, DecodedTexture> textures = new HashMap<>();

        while (offset < datBytes.length - 8) {
            int packed = buffer.getInt(offset + 4);
            int type = packed & 0x7F;
            int blockSize = ((packed >> 7) & 0x7FFFF) * 16;

            if (type == 0x20) { // Texture Chunk
                int dataStart = offset + 16;
                int dataEnd = offset + blockSize;
                if (dataEnd <= datBytes.length && dataEnd > dataStart) {
                    byte[] chunkData = new byte[dataEnd - dataStart];
                    System.arraycopy(datBytes, dataStart, chunkData, 0, chunkData.length);
                    DatByteReader reader = new DatByteReader(chunkData);
                    TextureParser.parseTexture(reader).ifPresent(tex -> {
                        String name = normalizeTextureName(tex.name());
                        if (!name.isEmpty()) {
                            textures.put(name, tex);
                            // Also map base prefix without variant digits (e.g. hm_ba1_1 -> hm_b, hm_hed11 -> hm_h)
                            int underscore = name.indexOf('_');
                            if (underscore >= 0 && underscore + 2 <= name.length()) {
                                String prefix = name.substring(0, underscore + 2); // e.g. hm_b
                                textures.putIfAbsent(prefix, tex);
                            }
                        }
                    });
                }
            }

            if (blockSize <= 0) break;
            offset += blockSize;
        }

        return textures;
    }

    // -------------------------------------------------------------------------
    // Internal 0x2A Model Parsing Implementation (matches ffxi-extractor)
    // -------------------------------------------------------------------------

    private static class BoneAssign {
        int leftL, rightL, flgL;
        int leftH, rightH, flgH;
    }

    private static class RawVertex {
        float x, y, z;
        float nx, ny, nz;
        int joint0, joint1;
        float weight0, weight1;
    }

    private static class WorkingGroup {
        final String textureName;
        final List<SkinnedVertex> vertices = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        WorkingGroup(String textureName) {
            this.textureName = textureName;
        }
    }

    private static List<SkinnedSubMesh> parseModelData(byte[] data, List<float[]> skelMatrices) {
        if (data.length < 52) {
            return Collections.emptyList();
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Read Model Header (52 bytes)
        buffer.position(0);
        buffer.get(); // ver
        buffer.get(); // nazo
        short type = buffer.getShort();
        short flip = buffer.getShort();
        int offsetPoly = buffer.getInt();
        buffer.getShort(); // polySuu
        int offsetBoneTbl = buffer.getInt();
        short boneTblSuu = buffer.getShort();
        int offsetWeight = buffer.getInt();
        buffer.getShort(); // weightSuu
        int offsetBone = buffer.getInt();
        short boneSuu = buffer.getShort();
        int offsetVertex = buffer.getInt();
        buffer.getShort(); // vertexSuu

        boolean isIndirect = (type & 0x80) != 0;
        boolean isCloth = (type & 0x7F) == 1;

        // 1. Read Bone Table
        int[] boneTbl = new int[boneTblSuu];
        int boneTblOffset = offsetBoneTbl * 2;
        if (boneTblOffset > 0 && boneTblOffset + boneTblSuu * 2 <= data.length) {
            buffer.position(boneTblOffset);
            for (int i = 0; i < boneTblSuu; i++) {
                boneTbl[i] = buffer.getShort() & 0xFFFF;
            }
        }

        // 2. Read Bone Assignments
        BoneAssign[] boneAssign = new BoneAssign[boneSuu / 2];
        int boneOffset = offsetBone * 2;
        if (boneOffset > 0 && boneOffset + boneSuu * 2 <= data.length) {
            buffer.position(boneOffset);
            for (int i = 0; i < boneSuu / 2; i++) {
                int low = buffer.getShort() & 0xFFFF;
                int high = buffer.getShort() & 0xFFFF;
                BoneAssign ba = new BoneAssign();
                ba.leftL = low & 0x7F;
                ba.rightL = (low >> 7) & 0x7F;
                ba.flgL = (low >> 14) & 3;
                ba.leftH = high & 0x7F;
                ba.rightH = (high >> 7) & 0x7F;
                ba.flgH = (high >> 14) & 3;
                boneAssign[i] = ba;
            }
        }

        // 3. Read Vertex Weights and Positions
        int weightOffset = offsetWeight * 2;
        int weight1 = 0;
        int weight2 = 0;
        if (weightOffset > 0 && weightOffset + 4 <= data.length) {
            buffer.position(weightOffset);
            weight1 = buffer.getShort() & 0xFFFF;
            weight2 = buffer.getShort() & 0xFFFF;
        }

        int vertexOffset = offsetVertex * 2;
        if (vertexOffset <= 0 || vertexOffset >= data.length || (weight1 + weight2) <= 0) {
            return Collections.emptyList();
        }

        // Transform normal half
        List<RawVertex> rawVerts = new ArrayList<>();
        transformVertices(data, vertexOffset, weight1, weight2, isCloth, isIndirect, boneTbl, boneAssign, false, skelMatrices, rawVerts);

        // Transform mirrored half if flip != 0
        if (flip != 0) {
            transformVertices(data, vertexOffset, weight1, weight2, isCloth, isIndirect, boneTbl, boneAssign, true, skelMatrices, rawVerts);
        }

        int N = weight1 + weight2; // original vertex count

        // 4. Parse Polygons and Texture Groups
        int polyOffset = offsetPoly * 2;
        if (polyOffset <= 0 || polyOffset >= data.length) {
            return Collections.emptyList();
        }

        buffer.position(polyOffset);
        List<WorkingGroup> groups = new ArrayList<>();
        WorkingGroup currentGroup = null;

        while (buffer.position() + 4 <= data.length) {
            int wf = buffer.getShort() & 0xFFFF;
            int ws = buffer.getShort() & 0xFFFF;

            if ((wf & 0x80F0) == 0x8010) {
                // Render properties
                buffer.position(buffer.position() + 42);
            } else if ((wf & 0x80F0) == 0x8000) {
                // Texture name
                buffer.position(buffer.position() - 2);
                byte[] texNameBytes = new byte[16];
                buffer.get(texNameBytes);
                String texName = new String(texNameBytes, StandardCharsets.UTF_8).trim().toLowerCase();
                String[] parts = texName.split("\\s+");
                texName = parts[parts.length - 1].replaceAll("[^a-zA-Z0-9_-]", "").trim();

                currentGroup = new WorkingGroup(texName);
                groups.add(currentGroup);
            } else if (wf == 0x5453) { // TriStrip
                if (buffer.position() + 30 > data.length) break;
                if (currentGroup == null) {
                    currentGroup = new WorkingGroup("default");
                    groups.add(currentGroup);
                }

                int idx1 = buffer.getShort() & 0xFFFF;
                int idx2 = buffer.getShort() & 0xFFFF;
                int idx3 = buffer.getShort() & 0xFFFF;
                float u1 = buffer.getFloat();
                float v1 = buffer.getFloat();
                float u2 = buffer.getFloat();
                float v2 = buffer.getFloat();
                float u3 = buffer.getFloat();
                float v3 = buffer.getFloat();

                // Original half
                addTriangle(currentGroup, rawVerts, idx1, u1, v1, idx2, u2, v2, idx3, u3, v3);
                if (flip != 0) {
                    // Mirrored half with reversed winding
                    addTriangle(currentGroup, rawVerts, idx1 + N, u1, v1, idx3 + N, u3, v3, idx2 + N, u2, v2);
                }

                int lastIdx1 = idx2;
                int lastIdx2 = idx3;
                float lastU1 = u2, lastV1 = v2;
                float lastU2 = u3, lastV2 = v3;

                for (int k = 0; k < ws - 1; k++) {
                    if (buffer.position() + 10 > data.length) break;
                    int idx = buffer.getShort() & 0xFFFF;
                    float u = buffer.getFloat();
                    float v = buffer.getFloat();

                    if (k % 2 == 0) {
                        addTriangle(currentGroup, rawVerts, lastIdx2, lastU2, lastV2, lastIdx1, lastU1, lastV1, idx, u, v);
                        if (flip != 0) {
                            addTriangle(currentGroup, rawVerts, lastIdx2 + N, lastU2, lastV2, idx + N, u, v, lastIdx1 + N, lastU1, lastV1);
                        }
                    } else {
                        addTriangle(currentGroup, rawVerts, lastIdx1, lastU1, lastV1, lastIdx2, lastU2, lastV2, idx, u, v);
                        if (flip != 0) {
                            addTriangle(currentGroup, rawVerts, lastIdx1 + N, lastU1, lastV1, idx + N, u, v, lastIdx2 + N, lastU2, lastV2);
                        }
                    }

                    lastIdx1 = lastIdx2;
                    lastU1 = lastU2;
                    lastV1 = lastV2;

                    lastIdx2 = idx;
                    lastU2 = u;
                    lastV2 = v;
                }
            } else if (wf == 0x0054) { // TriMesh
                if (currentGroup == null) {
                    currentGroup = new WorkingGroup("default");
                    groups.add(currentGroup);
                }

                for (int k = 0; k < ws; k++) {
                    if (buffer.position() + 30 > data.length) break;
                    int idx1 = buffer.getShort() & 0xFFFF;
                    int idx2 = buffer.getShort() & 0xFFFF;
                    int idx3 = buffer.getShort() & 0xFFFF;
                    float u1 = buffer.getFloat();
                    float v1 = buffer.getFloat();
                    float u2 = buffer.getFloat();
                    float v2 = buffer.getFloat();
                    float u3 = buffer.getFloat();
                    float v3 = buffer.getFloat();

                    addTriangle(currentGroup, rawVerts, idx1, u1, v1, idx2, u2, v2, idx3, u3, v3);
                    if (flip != 0) {
                        addTriangle(currentGroup, rawVerts, idx1 + N, u1, v1, idx3 + N, u3, v3, idx2 + N, u2, v2);
                    }
                }
            } else if (wf == 0x4353) { // Single Color Untextured TriStrip
                buffer.position(buffer.position() + ws * 20 + 8);
            } else if (wf == 0x0043) { // Untextured TriMesh
                buffer.position(buffer.position() + ws * 10);
            } else {
                break;
            }
        }

        List<SkinnedSubMesh> result = new ArrayList<>();
        for (WorkingGroup g : groups) {
            if (!g.vertices.isEmpty() && !g.indices.isEmpty()) {
                result.add(new SkinnedSubMesh(g.textureName, false, true, g.vertices, g.indices));
            }
        }
        return result;
    }

    private static void transformVertices(byte[] data, int vertexOffset, int weight1, int weight2,
                                          boolean isCloth, boolean isIndirect, int[] boneTbl, BoneAssign[] boneAssign,
                                          boolean flip, List<float[]> skelMatrices, List<RawVertex> outVerts) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(vertexOffset);

        // MV1 vertices (single bone)
        for (int i = 0; i < weight1; i++) {
            float vx = buffer.getFloat();
            float vy = buffer.getFloat();
            float vz = buffer.getFloat();
            float nx = 0f, ny = 1f, nz = 0f;
            if (!isCloth) {
                nx = buffer.getFloat();
                ny = buffer.getFloat();
                nz = buffer.getFloat();
            }

            int boneIdx = 0;
            if (i < boneAssign.length && boneAssign[i] != null) {
                BoneAssign ba = boneAssign[i];
                int tblIdx = flip ? ba.rightL : ba.leftL;
                boneIdx = resolveBoneIdx(tblIdx, boneTbl, isIndirect);
                if (skelMatrices != null && boneIdx < skelMatrices.size()) {
                    float[] m = skelMatrices.get(boneIdx);
                    if (flip) {
                        m = applyMirrorFlag(m, ba.flgL);
                    }
                    float[] t = mat4TransformPoint(m, vx, vy, vz, 1.0f);
                    vx = t[0]; vy = t[1]; vz = t[2];

                    float[] tn = mat4TransformPoint(m, nx, ny, nz, 0.0f);
                    nx = tn[0]; ny = tn[1]; nz = tn[2];
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len > 0.0001f) { nx /= len; ny /= len; nz /= len; }
                }
            }

            RawVertex rv = new RawVertex();
            rv.x = vx; rv.y = vy; rv.z = vz;
            rv.nx = nx; rv.ny = ny; rv.nz = nz;
            rv.joint0 = boneIdx;
            rv.joint1 = 0;
            rv.weight0 = 1.0f;
            rv.weight1 = 0.0f;
            outVerts.add(rv);
        }

        // MV2 vertices (dual bones)
        for (int i = 0; i < weight2; i++) {
            float x1 = buffer.getFloat();
            float x2 = buffer.getFloat();
            float y1 = buffer.getFloat();
            float y2 = buffer.getFloat();
            float z1 = buffer.getFloat();
            float z2 = buffer.getFloat();
            float w1 = buffer.getFloat();
            float w2 = buffer.getFloat();

            float nx1 = 0f, ny1 = 1f, nz1 = 0f;
            float nx2 = 0f, ny2 = 0f, nz2 = 0f;
            if (!isCloth) {
                nx1 = buffer.getFloat();
                nx2 = buffer.getFloat();
                ny1 = buffer.getFloat();
                ny2 = buffer.getFloat();
                nz1 = buffer.getFloat();
                nz2 = buffer.getFloat();
            }

            float rx = x1, ry = y1, rz = z1;
            float rnx = nx1, rny = ny1, rnz = nz1;
            int boneIdxL = 0;
            int boneIdxH = 0;

            int bIdx = weight1 + i;
            if (bIdx < boneAssign.length && boneAssign[bIdx] != null) {
                BoneAssign ba = boneAssign[bIdx];
                int tblIdxL = flip ? ba.rightL : ba.leftL;
                int tblIdxH = flip ? ba.rightH : ba.leftH;
                boneIdxL = resolveBoneIdx(tblIdxL, boneTbl, isIndirect);
                boneIdxH = resolveBoneIdx(tblIdxH, boneTbl, isIndirect);

                rx = 0; ry = 0; rz = 0;
                rnx = 0; rny = 0; rnz = 0;

                if (skelMatrices != null && boneIdxL < skelMatrices.size()) {
                    float[] m = skelMatrices.get(boneIdxL);
                    if (flip) m = applyMirrorFlag(m, ba.flgL);
                    float[] t = mat4TransformPoint(m, x1, y1, z1, w1);
                    rx += t[0]; ry += t[1]; rz += t[2];

                    float[] tn = mat4TransformPoint(m, nx1, ny1, nz1, 0.0f);
                    rnx += tn[0] * w1; rny += tn[1] * w1; rnz += tn[2] * w1;
                }
                if (skelMatrices != null && boneIdxH < skelMatrices.size()) {
                    float[] m = skelMatrices.get(boneIdxH);
                    if (flip) m = applyMirrorFlag(m, ba.flgH);
                    float[] t = mat4TransformPoint(m, x2, y2, z2, w2);
                    rx += t[0]; ry += t[1]; rz += t[2];

                    float[] tn = mat4TransformPoint(m, nx2, ny2, nz2, 0.0f);
                    rnx += tn[0] * w2; rny += tn[1] * w2; rnz += tn[2] * w2;
                }
                float len = (float) Math.sqrt(rnx * rnx + rny * rny + rnz * rnz);
                if (len > 0.0001f) { rnx /= len; rny /= len; rnz /= len; }
            }

            RawVertex rv = new RawVertex();
            rv.x = rx; rv.y = ry; rv.z = rz;
            rv.nx = rnx; rv.ny = rny; rv.nz = rnz;
            rv.joint0 = boneIdxL;
            rv.joint1 = boneIdxH;
            rv.weight0 = w1;
            rv.weight1 = w2;
            outVerts.add(rv);
        }
    }

    private static int resolveBoneIdx(int tblIdx, int[] boneTbl, boolean isIndirect) {
        if (isIndirect) {
            return (tblIdx < boneTbl.length) ? boneTbl[tblIdx] : 0;
        }
        return tblIdx;
    }

    private static float[] applyMirrorFlag(float[] m, int flg) {
        float[] result = m.clone();
        if (flg == 1) {
            result[0] = -m[0]; result[1] = -m[1]; result[2] = -m[2]; result[3] = -m[3];
        } else if (flg == 2) {
            result[4] = -m[4]; result[5] = -m[5]; result[6] = -m[6]; result[7] = -m[7];
        } else if (flg == 3) {
            result[8] = -m[8]; result[9] = -m[9]; result[10] = -m[10]; result[11] = -m[11];
        }
        return result;
    }

    private static void addTriangle(WorkingGroup group, List<RawVertex> rawV,
                                    int idx1, float u1, float v1,
                                    int idx2, float u2, float v2,
                                    int idx3, float u3, float v3) {
        addVertex(group, rawV, idx1, u1, v1);
        addVertex(group, rawV, idx2, u2, v2);
        addVertex(group, rawV, idx3, u3, v3);
    }

    private static void addVertex(WorkingGroup group, List<RawVertex> rawV, int idx, float u, float v) {
        int outIdx = group.vertices.size();
        if (idx >= 0 && idx < rawV.size()) {
            RawVertex rv = rawV.get(idx);
            group.vertices.add(new SkinnedVertex(
                    new Vector3f(rv.x, rv.y, rv.z),
                    new Vector3f(rv.nx, rv.ny, rv.nz),
                    u, v,
                    rv.joint0, rv.joint1,
                    rv.weight0, rv.weight1,
                    128, 128, 128, 255
            ));
        } else {
            group.vertices.add(new SkinnedVertex(
                    new Vector3f(0f, 0f, 0f),
                    new Vector3f(0f, 1f, 0f),
                    u, v,
                    0, 0,
                    1.0f, 0.0f,
                    128, 128, 128, 255
            ));
        }
        group.indices.add(outIdx);
    }

    private static float[] quatToMatrix(float qi, float qj, float qk, float qw, float tx, float ty, float tz) {
        float x2 = qi + qi, y2 = qj + qj, z2 = qk + qk;
        float xx = qi * x2, xy = qi * y2, xz = qi * z2;
        float yy = qj * y2, yz = qj * z2, zz = qk * z2;
        float wx = qw * x2, wy = qw * y2, wz = qw * z2;
        return new float[]{
                1 - yy - zz, xy + wz, xz - wy, 0,
                xy - wz, 1 - xx - zz, yz + wx, 0,
                xz + wy, yz - wx, 1 - xx - yy, 0,
                tx, ty, tz, 1
        };
    }

    private static float[] mat4Multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 4; k++) {
                    r[i * 4 + j] += a[i * 4 + k] * b[k * 4 + j];
                }
            }
        }
        return r;
    }

    public static float[] mat4TransformPoint(float[] m, float x, float y, float z, float w) {
        return new float[]{
                x * m[0] + y * m[4] + z * m[8] + w * m[12],
                x * m[1] + y * m[5] + z * m[9] + w * m[13],
                x * m[2] + y * m[6] + z * m[10] + w * m[14]
        };
    }
}
