package com.ffxi.viewer.environment;

import com.ffxi.model.ZoneEnvironmentResource;
import com.ffxi.viewer.shader.GlShaderUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Authentic retail FFXI &amp; XIM hemispherical sky dome renderer.
 * Renders a dome composed of 8 elevation rings/slices (plus horizon-to-nadir skirt)
 * and N radial spokes (default 24) using dynamic {@code GL_TRIANGLE_STRIP} ribbons carrying
 * smooth vertex colors from {@code S2F_Environment}.
 */
public final class AuthenticSkyDome implements AutoCloseable {

    private static final String SKY_VERT_SRC = """
            #version 460 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec4 aColor;

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform vec3 uCameraPos;

            out vec4 vColor;

            void main() {
                vColor = aColor;
                vec4 worldPos = vec4(aPos + uCameraPos, 1.0);
                gl_Position = uProjMatrix * uViewMatrix * worldPos;
            }
            """;

    private static final String SKY_FRAG_SRC = """
            #version 460 core
            in vec4 vColor;
            out vec4 FragColor;

            uniform float uBrightness;

            void main() {
                FragColor = vec4(vColor.rgb * uBrightness, 1.0);
            }
            """;

    private final int defaultSpokeCount;
    private final float defaultRadius;
    private final int shaderProgram;
    private final int uProjLoc;
    private final int uViewLoc;
    private final int uCameraPosLoc;
    private final int uBrightnessLoc;

    private int vaoId = 0;
    private int vboId = 0;
    private int totalVertices = 0;
    private int activeSpokeCount;
    private int activeBandCount = 7;

    public AuthenticSkyDome(float radius, int spokeCount) {
        this.defaultRadius = Math.max(100.0f, radius);
        this.defaultSpokeCount = Math.max(8, spokeCount);
        this.activeSpokeCount = this.defaultSpokeCount;

        shaderProgram = GlShaderUtil.createProgram(SKY_VERT_SRC, SKY_FRAG_SRC, "AuthenticSkyDome");

        uProjLoc = glGetUniformLocation(shaderProgram, "uProjMatrix");
        uViewLoc = glGetUniformLocation(shaderProgram, "uViewMatrix");
        uCameraPosLoc = glGetUniformLocation(shaderProgram, "uCameraPos");
        uBrightnessLoc = glGetUniformLocation(shaderProgram, "uBrightness");

        vaoId = glGenVertexArrays();
        vboId = glGenBuffers();
    }

    public AuthenticSkyDome() {
        this(1200.0f, 24);
    }

    /**
     * Builds and uploads the 8-slice hemispherical triangle strip vertex mesh from a fallback weather preset.
     */
    public void updateMesh(WeatherPreset preset) {
        if (preset == null || preset.skySlices().size() < 8) {
            return;
        }
        List<WeatherPreset.SkySlice> slices = new ArrayList<>(9);
        slices.add(new WeatherPreset.SkySlice(-1.0f, preset.skySlices().get(0).color()));
        slices.addAll(preset.skySlices().subList(0, 8));
        uploadSlices(slices, defaultRadius, defaultSpokeCount);
    }

    /**
     * Builds and uploads the authentic 8-slice + nadir sky dome from a resolved retail {@code S2F_Environment}.
     */
    public void updateFromEnvironment(ZoneEnvironmentTable.ResolvedEnvironment env) {
        if (env == null || !env.hasDome() || env.skyColors() == null || env.skyStops() == null) {
            return;
        }
        Vector3f[] colors = env.skyColors();
        float[] stops = env.skyStops();
        int count = Math.min(ZoneEnvironmentResource.SKY_SLICE_COUNT, Math.min(colors.length, stops.length));
        if (count < 2) {
            return;
        }
        List<WeatherPreset.SkySlice> slices = new ArrayList<>(count + 1);
        slices.add(new WeatherPreset.SkySlice(-1.0f, new Vector4f(colors[0].x, colors[0].y, colors[0].z, 1.0f)));
        for (int i = 0; i < count; i++) {
            slices.add(new WeatherPreset.SkySlice(stops[i], new Vector4f(colors[i].x, colors[i].y, colors[i].z, 1.0f)));
        }
        float useRadius = env.skyRadius() > 100.0f ? env.skyRadius() : defaultRadius;
        int useSpokes = env.spokeCount() >= 8 ? env.spokeCount() : defaultSpokeCount;
        uploadSlices(slices, useRadius, useSpokes);
    }

    private void uploadSlices(List<WeatherPreset.SkySlice> slices, float meshRadius, int spokes) {
        int bands = slices.size() - 1;
        if (bands <= 0) {
            return;
        }
        float thetaStep = (float) (2.0 * Math.PI / spokes);

        List<List<Vector3f>> layers = new ArrayList<>(slices.size());
        for (WeatherPreset.SkySlice slice : slices) {
            float phi = (float) (0.5 * Math.PI * slice.elevation());
            float layerRadius = meshRadius * (float) Math.cos(phi);
            float layerY = -meshRadius * (float) Math.sin(phi);

            List<Vector3f> layer = new ArrayList<>(spokes);
            for (int j = 0; j < spokes; j++) {
                float theta = j * thetaStep;
                float x = layerRadius * (float) Math.sin(theta);
                float z = layerRadius * (float) Math.cos(theta);
                layer.add(new Vector3f(x, layerY, z));
            }
            layers.add(layer);
        }

        int verticesPerBand = (spokes + 1) * 2;
        int totalVerts = bands * verticesPerBand;
        ByteBuffer buffer = BufferUtils.createByteBuffer(totalVerts * 28);

        for (int i = 0; i < bands; i++) {
            Vector4f c0 = slices.get(i).color();
            Vector4f c1 = slices.get(i + 1).color();

            for (int j = 0; j < spokes; j++) {
                Vector3f p0 = layers.get(i).get(j);
                Vector3f p1 = layers.get(i + 1).get(j);

                buffer.putFloat(p0.x).putFloat(p0.y).putFloat(p0.z);
                buffer.putFloat(c0.x).putFloat(c0.y).putFloat(c0.z).putFloat(c0.w);

                buffer.putFloat(p1.x).putFloat(p1.y).putFloat(p1.z);
                buffer.putFloat(c1.x).putFloat(c1.y).putFloat(c1.z).putFloat(c1.w);
            }

            Vector3f p0 = layers.get(i).get(0);
            Vector3f p1 = layers.get(i + 1).get(0);
            buffer.putFloat(p0.x).putFloat(p0.y).putFloat(p0.z);
            buffer.putFloat(c0.x).putFloat(c0.y).putFloat(c0.z).putFloat(c0.w);
            buffer.putFloat(p1.x).putFloat(p1.y).putFloat(p1.z);
            buffer.putFloat(c1.x).putFloat(c1.y).putFloat(c1.z).putFloat(c1.w);
        }
        buffer.flip();

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0);
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
        this.activeSpokeCount = spokes;
        this.activeBandCount = bands;
        this.totalVertices = totalVerts;
    }

    /**
     * Renders the authentic sky dome backdrop behind all world geometry.
     */
    public void render(Matrix4f projMatrix, Matrix4f viewMatrix, Vector3f cameraPos, float brightness) {
        if (vaoId == 0 || totalVertices == 0) {
            return;
        }

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glUseProgram(shaderProgram);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(uProjLoc, false, projMatrix.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(uViewLoc, false, viewMatrix.get(stack.mallocFloat(16)));
        }

        glUniform3f(uCameraPosLoc, cameraPos.x, cameraPos.y, cameraPos.z);
        glUniform1f(uBrightnessLoc, brightness);

        glBindVertexArray(vaoId);

        int verticesPerBand = (activeSpokeCount + 1) * 2;
        for (int i = 0; i < activeBandCount; i++) {
            glDrawArrays(GL_TRIANGLE_STRIP, i * verticesPerBand, verticesPerBand);
        }

        glBindVertexArray(0);
        glUseProgram(0);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    public int getSpokeCount() {
        return activeSpokeCount;
    }

    public float getRadius() {
        return defaultRadius;
    }

    public int getTotalVertices() {
        return totalVertices;
    }

    @Override
    public void close() {
        if (vboId != 0) glDeleteBuffers(vboId);
        if (vaoId != 0) glDeleteVertexArrays(vaoId);
        if (shaderProgram != 0) glDeleteProgram(shaderProgram);
        vboId = 0;
        vaoId = 0;
    }
}
