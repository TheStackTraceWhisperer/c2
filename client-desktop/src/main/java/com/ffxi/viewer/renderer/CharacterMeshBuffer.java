package com.ffxi.viewer.renderer;

import com.ffxi.model.BgraColor;
import com.ffxi.model.SkeletonMeshFaceVertex;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshVertex;
import com.ffxi.model.Vec3f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_FLOAT;
import static org.lwjgl.opengl.GL33C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL33C.glDrawArrays;
import static org.lwjgl.opengl.GL33C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glGenVertexArrays;
import static org.lwjgl.opengl.GL30C.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL33C.glVertexAttribPointer;

public final class CharacterMeshBuffer implements AutoCloseable {
    public static final int STRIDE_BYTES = 0x44;

    private final int vaoId;
    private final int vboId;
    private final int vertexCount;
    private final String textureName;
    private final boolean mirrored;

    public CharacterMeshBuffer(SkeletonMeshMesh mesh) {
        this.textureName = mesh.textureName();
        this.mirrored = mesh.mirrored();
        this.vaoId = glGenVertexArrays();
        this.vboId = glGenBuffers();

        ExpandedMesh expanded = expandToTriangleList(mesh);
        this.vertexCount = expanded.count();
        ByteBuffer vertexData = buildVertexBuffer(expanded, mesh.vertices(), mesh.resolvedVertices());

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);
        configureAttributes();
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        System.out.printf("[MESH] %-30s  srcVerts=%d  expandedVerts=%d  triangles=%d  strip=%b%n",
                textureName.isBlank() ? "(no texture)" : textureName,
                mesh.vertices().size(),
                expanded.count(),
                expanded.count() / 3,
                expanded.wasStrip());
    }

    public String textureName() {
        return textureName;
    }

    public boolean mirrored() {
        return mirrored;
    }

    public void draw() {
        if (vertexCount == 0) {
            return;
        }
        glBindVertexArray(vaoId);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    @Override
    public void close() {
        glDeleteBuffers(vboId);
        glDeleteVertexArrays(vaoId);
    }

    private record ExpandedMesh(
            int[] faceIdx,
            int[] resolIdx,
            boolean wasStrip
    ) {
        int count() { return faceIdx.length; }
    }

    private static ExpandedMesh expandToTriangleList(SkeletonMeshMesh mesh) {
        List<SkeletonMeshFaceVertex> src = mesh.vertices();
        int n = src.size();

        if (n < 3) {
            return new ExpandedMesh(new int[0], new int[0], false);
        }

        if (mesh.vertexCount() == mesh.triangleCount() * 3) {
            int triCount = (n / 3) * 3;
            int[] fi = new int[triCount];
            int[] ri = new int[triCount];
            for (int i = 0; i + 2 < triCount; i += 3) {
                fi[i]     = i;     ri[i]     = i;
                fi[i + 1] = i + 2; ri[i + 1] = i + 2;
                fi[i + 2] = i + 1; ri[i + 2] = i + 1;
            }
            return new ExpandedMesh(fi, ri, false);
        }

        int maxTris = n - 2;
        int[] fi = new int[maxTris * 3];
        int[] ri = new int[maxTris * 3];
        int out = 0;
        for (int i = 2; i < n; i++) {
            int a, b, c;
            if ((i & 1) == 0) {
                a = i - 2; b = i; c = i - 1;
            } else {
                a = i - 1; b = i; c = i - 2;
            }
            if (a == b || b == c || a == c) continue;
            fi[out] = a; ri[out] = a; out++;
            fi[out] = b; ri[out] = b; out++;
            fi[out] = c; ri[out] = c; out++;
        }
        int[] trimFi = new int[out];
        int[] trimRi = new int[out];
        System.arraycopy(fi, 0, trimFi, 0, out);
        System.arraycopy(ri, 0, trimRi, 0, out);
        return new ExpandedMesh(trimFi, trimRi, true);
    }

    private static ByteBuffer buildVertexBuffer(
            ExpandedMesh expanded,
            List<SkeletonMeshFaceVertex> srcFace,
            List<SkeletonMeshVertex> srcResolved
    ) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(expanded.count() * STRIDE_BYTES);

        for (int i = 0; i < expanded.count(); i++) {
            SkeletonMeshFaceVertex faceVertex = srcFace.get(expanded.faceIdx()[i]);
            SkeletonMeshVertex vertex = srcResolved.get(expanded.resolIdx()[i]);

            putVec3(buffer, vertex.p0());
            putVec3(buffer, vertex.p1());
            putVec3(buffer, vertex.n0());
            putVec3(buffer, vertex.n1());
            buffer.putFloat(faceVertex.u());
            buffer.putFloat(faceVertex.v());
            buffer.putFloat(vertex.joint0Weight());
            buffer.put((byte) clampJointIndex(vertex.jointIndex0()));
            buffer.put((byte) clampJointIndex(vertex.jointIndex1()));
            putColorRgba(buffer, faceVertex.color());
            buffer.put((byte) 0);
            buffer.put((byte) 0);
        }

        buffer.flip();
        return buffer;
    }

    private static void configureAttributes() {
        for (int attribute = 0; attribute <= 8; attribute++) {
            glEnableVertexAttribArray(attribute);
        }

        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE_BYTES, 0L);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, STRIDE_BYTES, 12L);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, STRIDE_BYTES, 24L);
        glVertexAttribPointer(3, 3, GL_FLOAT, false, STRIDE_BYTES, 36L);
        glVertexAttribPointer(4, 2, GL_FLOAT, false, STRIDE_BYTES, 48L);
        glVertexAttribPointer(5, 1, GL_FLOAT, false, STRIDE_BYTES, 56L);
        glVertexAttribIPointer(6, 1, GL_UNSIGNED_BYTE, STRIDE_BYTES, 60L);
        glVertexAttribIPointer(7, 1, GL_UNSIGNED_BYTE, STRIDE_BYTES, 61L);
        glVertexAttribPointer(8, 4, GL_UNSIGNED_BYTE, true, STRIDE_BYTES, 62L);
    }

    private static void putVec3(ByteBuffer buffer, Vec3f vec) {
        if (vec == null) {
            buffer.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
            return;
        }
        buffer.putFloat(vec.x()).putFloat(vec.y()).putFloat(vec.z());
    }

    private static void putColorRgba(ByteBuffer buffer, BgraColor color) {
        int r = color != null ? color.r() : 0x80;
        int g = color != null ? color.g() : 0x80;
        int b = color != null ? color.b() : 0x80;
        int a = color != null ? color.a() : 0x80;
        buffer.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
    }

    private static int clampJointIndex(Integer jointIndex) {
        if (jointIndex == null) {
            return 0;
        }
        return Math.max(0, Math.min(255, jointIndex));
    }
}
