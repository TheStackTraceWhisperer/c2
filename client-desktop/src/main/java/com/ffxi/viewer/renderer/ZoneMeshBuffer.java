package com.ffxi.viewer.renderer;

import com.ffxi.viewer.model.ZoneMeshMath;
import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneMeshVertex;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

public final class ZoneMeshBuffer implements AutoCloseable {
    public static final int STRIDE_BYTES = 36; // 3*4 (pos) + 3*4 (normal) + 2*4 (uv) + 4 (color)

    private final int vaoId;
    private final int vboId;
    private final int eboId;
    private final int indexCount;
    private final String textureName;
    private final boolean renderBlend;

    public ZoneMeshBuffer(ZoneMeshChunk chunk) {
        this.textureName = chunk.textureName();
        this.renderBlend = chunk.renderBlend();
        this.vaoId = glGenVertexArrays();
        this.vboId = glGenBuffers();
        this.eboId = glGenBuffers();

        List<ZoneMeshVertex> vertices = chunk.vertices();
        List<Integer> indices = expandTriangles(chunk);
        this.indexCount = indices.size();

        ByteBuffer vertexData = BufferUtils.createByteBuffer(vertices.size() * STRIDE_BYTES);
        for (ZoneMeshVertex v : vertices) {
            vertexData.putFloat(ZoneMeshMath.sanitizeFinite(-v.px())).putFloat(ZoneMeshMath.sanitizeFinite(-v.py())).putFloat(ZoneMeshMath.sanitizeFinite(v.pz()));
            vertexData.putFloat(ZoneMeshMath.sanitizeFinite(-v.nx())).putFloat(ZoneMeshMath.sanitizeFinite(-v.ny())).putFloat(ZoneMeshMath.sanitizeFinite(v.nz()));
            vertexData.putFloat(v.u()).putFloat(v.v());
            vertexData.put((byte) v.colorR()).put((byte) v.colorG()).put((byte) v.colorB()).put((byte) v.colorA());
        }
        vertexData.flip();

        glBindVertexArray(vaoId);

        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);

        // Indices
        ByteBuffer indexData = BufferUtils.createByteBuffer(indices.size() * Integer.BYTES);
        for (int idx : indices) {
            indexData.putInt(idx);
        }
        indexData.flip();

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexData, GL_STATIC_DRAW);

        // Attributes
        glEnableVertexAttribArray(0); // Pos
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE_BYTES, 0L);

        glEnableVertexAttribArray(1); // Normal
        glVertexAttribPointer(1, 3, GL_FLOAT, false, STRIDE_BYTES, 12L);

        glEnableVertexAttribArray(2); // UV
        glVertexAttribPointer(2, 2, GL_FLOAT, false, STRIDE_BYTES, 24L);

        glEnableVertexAttribArray(3); // Color
        glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, STRIDE_BYTES, 32L);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public String textureName() {
        return textureName;
    }

    public boolean renderBlend() {
        return renderBlend;
    }

    public void draw() {
        if (indexCount == 0) return;
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
    }

    @Override
    public void close() {
        glDeleteBuffers(eboId);
        glDeleteBuffers(vboId);
        glDeleteVertexArrays(vaoId);
    }

    private static List<Integer> expandTriangles(ZoneMeshChunk chunk) {
        if (!ZoneMeshMath.hasValidVertices(chunk)) {
            return List.of();
        }
        return ZoneMeshMath.expandTriangles(chunk);
    }
}
