package com.ffxi.viewer.renderer;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.ffxi.viewer.shader.GlShaderUtil;

import static org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_BLEND;
import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL33C.GL_FLOAT;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL33C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glActiveTexture;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindTexture;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glBlendFunc;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glDeleteProgram;
import static org.lwjgl.opengl.GL33C.glDeleteTextures;
import static org.lwjgl.opengl.GL33C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL33C.glDisable;
import static org.lwjgl.opengl.GL33C.glDrawArrays;
import static org.lwjgl.opengl.GL33C.glEnable;
import static org.lwjgl.opengl.GL33C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glGenTextures;
import static org.lwjgl.opengl.GL33C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glPixelStorei;
import static org.lwjgl.opengl.GL33C.glTexImage2D;
import static org.lwjgl.opengl.GL33C.glTexParameteri;
import static org.lwjgl.opengl.GL33C.glUniform1i;
import static org.lwjgl.opengl.GL33C.glUniform2f;
import static org.lwjgl.opengl.GL33C.glUseProgram;
import static org.lwjgl.opengl.GL33C.glVertexAttribPointer;

/**
 * OpenGL 3.3 batched 2D text renderer backed by {@link FontMojiRenderer}'s retail
 * {@code ROM/0/1.DAT} glyph atlas. Used for authentic in-scene nameplates and HUD text.
 */
public final class FontMojiGlRenderer implements AutoCloseable {

    private static final int FLOATS_PER_VERTEX = 8;
    private static final int VERTICES_PER_QUAD = 6;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    private static final String VERT_SHADER = """
            #version 330 core
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUv;
            layout(location=2) in vec4 aColor;

            uniform vec2 uViewport;

            out vec2 vUv;
            out vec4 vColor;

            void main() {
                vec2 ndc = vec2(
                    (aPos.x / uViewport.x) * 2.0 - 1.0,
                    1.0 - (aPos.y / uViewport.y) * 2.0
                );
                gl_Position = vec4(ndc, 0.0, 1.0);
                vUv = aUv;
                vColor = aColor;
            }
            """;

    private static final String FRAG_SHADER = """
            #version 330 core
            in vec2 vUv;
            in vec4 vColor;

            uniform sampler2D uFontAtlas;

            out vec4 fragColor;

            void main() {
                vec4 tex = texture(uFontAtlas, vUv);
                if (tex.a < 0.03) {
                    discard;
                }
                fragColor = tex * vColor;
            }
            """;

    private final FontMojiRenderer font;
    private final int programId;
    private final int uViewportLoc;
    private final int uFontAtlasLoc;
    private final int textureId;
    private final int vaoId;
    private final int vboId;

    private float[] vertexData = new float[2048 * FLOATS_PER_VERTEX];
    private int vertexCount = 0;
    private FloatBuffer uploadBuffer = BufferUtils.createFloatBuffer(2048 * FLOATS_PER_VERTEX);
    private int viewportWidth = 1;
    private int viewportHeight = 1;
    private boolean closed = false;

    public FontMojiGlRenderer(FontMojiRenderer font) {
        this.font = Objects.requireNonNull(font, "font");
        this.programId = GlShaderUtil.createProgram(VERT_SHADER, FRAG_SHADER, "FontMojiGlRenderer");
        this.uViewportLoc = glGetUniformLocation(programId, "uViewport");
        this.uFontAtlasLoc = glGetUniformLocation(programId, "uFontAtlas");

        byte[] rgba = font.toRgbaBytes();
        ByteBuffer pixelBuf = BufferUtils.createByteBuffer(rgba.length);
        pixelBuf.put(rgba).flip();

        this.textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, font.getTexWidth(), font.getTexHeight(), 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixelBuf);
        glBindTexture(GL_TEXTURE_2D, 0);

        this.vaoId = glGenVertexArrays();
        this.vboId = glGenBuffers();
        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, STRIDE_BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE_BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, STRIDE_BYTES, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public FontMojiRenderer getFont() {
        return font;
    }

    public void begin(int viewportWidth, int viewportHeight) {
        this.viewportWidth = Math.max(1, viewportWidth);
        this.viewportHeight = Math.max(1, viewportHeight);
        this.vertexCount = 0;
    }

    public void queueText(String text, float x, float y, float scale, float r, float g, float b, float a) {
        if (closed || text == null || text.isEmpty() || scale <= 0.0f) {
            return;
        }
        queuePass(font.layoutString(text, x + scale, y + scale, scale), 0.02f, 0.03f, 0.06f, 0.85f * a);
        queuePass(font.layoutString(text, x, y, scale), r, g, b, a);
    }

    public void queueCenteredText(String text, float centerX, float topY, float scale, float r, float g, float b, float a) {
        if (closed || text == null || text.isEmpty() || scale <= 0.0f) {
            return;
        }
        float width = font.measureStringWidth(text, scale);
        float startX = Math.round(centerX - width * 0.5f);
        float startY = Math.round(topY);
        queueText(text, startX, startY, scale, r, g, b, a);
    }

    public void flush() {
        if (closed || vertexCount == 0) {
            return;
        }
        int floatCount = vertexCount * FLOATS_PER_VERTEX;
        if (uploadBuffer.capacity() < floatCount) {
            uploadBuffer = BufferUtils.createFloatBuffer(Math.max(floatCount, uploadBuffer.capacity() * 2));
        }
        uploadBuffer.clear();
        uploadBuffer.put(vertexData, 0, floatCount).flip();

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glUseProgram(programId);
        glUniform2f(uViewportLoc, (float) viewportWidth, (float) viewportHeight);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glUniform1i(uFontAtlasLoc, 0);

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, uploadBuffer, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);

        vertexCount = 0;
    }

    private void queuePass(List<FontMojiRenderer.GlyphQuad> quads, float r, float g, float b, float a) {
        if (quads.isEmpty()) {
            return;
        }
        ensureCapacity(vertexCount + quads.size() * VERTICES_PER_QUAD);
        for (FontMojiRenderer.GlyphQuad q : quads) {
            pushVertex(q.x0(), q.y0(), q.u0(), q.v0(), r, g, b, a);
            pushVertex(q.x0(), q.y1(), q.u0(), q.v1(), r, g, b, a);
            pushVertex(q.x1(), q.y1(), q.u1(), q.v1(), r, g, b, a);

            pushVertex(q.x0(), q.y0(), q.u0(), q.v0(), r, g, b, a);
            pushVertex(q.x1(), q.y1(), q.u1(), q.v1(), r, g, b, a);
            pushVertex(q.x1(), q.y0(), q.u1(), q.v0(), r, g, b, a);
        }
    }

    private void pushVertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        int base = vertexCount * FLOATS_PER_VERTEX;
        vertexData[base] = x;
        vertexData[base + 1] = y;
        vertexData[base + 2] = u;
        vertexData[base + 3] = v;
        vertexData[base + 4] = r;
        vertexData[base + 5] = g;
        vertexData[base + 6] = b;
        vertexData[base + 7] = a;
        vertexCount++;
    }

    private void ensureCapacity(int requiredVertices) {
        int requiredFloats = requiredVertices * FLOATS_PER_VERTEX;
        if (vertexData.length < requiredFloats) {
            vertexData = Arrays.copyOf(vertexData, Math.max(requiredFloats, vertexData.length * 2));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        glDeleteBuffers(vboId);
        glDeleteVertexArrays(vaoId);
        glDeleteTextures(textureId);
        glDeleteProgram(programId);
    }
}
