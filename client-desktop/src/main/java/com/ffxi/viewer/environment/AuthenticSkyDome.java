package com.ffxi.viewer.environment;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Authentic retail FFXI & XIM hemispherical sky dome renderer.
 * Instead of a generic cube map, retail FFXI renders a dome composed of 8 elevation rings/slices
 * and N radial spokes (default 24). Ring vertices are positioned along concentric latitude circles:
 *   phi = -0.5 * PI * elevation
 * and rendered as dynamic GL_TRIANGLE_STRIP ribbons carrying smooth vertex colors.
 * Rendered with glDepthMask(false) so it always serves as the atmospheric backdrop for map geometry.
 */
public final class AuthenticSkyDome implements AutoCloseable {

    private static final String SKY_VERT_SRC = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec4 aColor;

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform vec3 uCameraPos;

            out vec4 vColor;

            void main() {
                vColor = aColor;
                // Center sky dome around camera so it never shifts during navigation
                vec4 worldPos = vec4(aPos + uCameraPos, 1.0);
                gl_Position = uProjMatrix * uViewMatrix * worldPos;
            }
            """;

    private static final String SKY_FRAG_SRC = """
            #version 330 core
            in vec4 vColor;
            out vec4 FragColor;

            uniform float uBrightness;

            void main() {
                FragColor = vec4(vColor.rgb * uBrightness, 1.0);
            }
            """;

    private final int spokeCount;
    private final float radius;
    private final int shaderProgram;
    private final int uProjLoc;
    private final int uViewLoc;
    private final int uCameraPosLoc;
    private final int uBrightnessLoc;

    private int vaoId = 0;
    private int vboId = 0;
    private int totalVertices = 0;

    public AuthenticSkyDome(float radius, int spokeCount) {
        this.radius = Math.max(100.0f, radius);
        this.spokeCount = Math.max(8, spokeCount);

        // Compile sky dome shader
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, SKY_VERT_SRC);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile SkyDome vertex shader: " + glGetShaderInfoLog(vs));
        }

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, SKY_FRAG_SRC);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile SkyDome fragment shader: " + glGetShaderInfoLog(fs));
        }

        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vs);
        glAttachShader(shaderProgram, fs);
        glLinkProgram(shaderProgram);
        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to link SkyDome program: " + glGetProgramInfoLog(shaderProgram));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);

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
     * Builds and uploads the 8-slice hemispherical triangle strip vertex mesh from the given weather preset.
     */
    public void updateMesh(WeatherPreset preset) {
        if (preset == null || preset.skySlices().size() < 8) {
            return;
        }

        List<WeatherPreset.SkySlice> slices = preset.skySlices();
        float thetaStep = (float) (2.0 * Math.PI / spokeCount);

        // Compute 3D positions for each layer
        List<List<Vector3f>> layers = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            float phi = (float) (0.5 * Math.PI * slices.get(i).elevation());
            float layerRadius = radius * (float) Math.cos(phi);
            float layerY = radius * (float) Math.sin(phi);

            List<Vector3f> layer = new ArrayList<>(spokeCount);
            for (int j = 0; j < spokeCount; j++) {
                float theta = j * thetaStep;
                float x = layerRadius * (float) Math.sin(theta);
                float z = layerRadius * (float) Math.cos(theta);
                layer.add(new Vector3f(x, layerY, z));
            }
            layers.add(layer);
        }

        // Generate triangle strip ribbons: 7 bands connecting layers[i] to layers[i+1]
        // Stride: 3 floats pos + 4 floats color = 7 floats (28 bytes)
        int verticesPerBand = (spokeCount + 1) * 2;
        int totalVerts = 7 * verticesPerBand;
        ByteBuffer buffer = BufferUtils.createByteBuffer(totalVerts * 28);

        for (int i = 0; i < 7; i++) {
            Vector4f c0 = slices.get(i).color();
            Vector4f c1 = slices.get(i + 1).color();

            for (int j = 0; j < spokeCount; j++) {
                Vector3f p0 = layers.get(i).get(j);
                Vector3f p1 = layers.get(i + 1).get(j);

                // Vertex on lower ring
                buffer.putFloat(p0.x).putFloat(p0.y).putFloat(p0.z);
                buffer.putFloat(c0.x).putFloat(c0.y).putFloat(c0.z).putFloat(c0.w);

                // Vertex on upper ring
                buffer.putFloat(p1.x).putFloat(p1.y).putFloat(p1.z);
                buffer.putFloat(c1.x).putFloat(c1.y).putFloat(c1.z).putFloat(c1.w);
            }

            // Close the loop
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

        // aPos (location = 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0);
        glEnableVertexAttribArray(0);

        // aColor (location = 1)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
        this.totalVertices = totalVerts;
    }

    /**
     * Renders the authentic sky dome backdrop.
     */
    public void render(Matrix4f projMatrix, Matrix4f viewMatrix, Vector3f cameraPos, float brightness) {
        if (vaoId == 0 || totalVertices == 0) {
            return;
        }

        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glUseProgram(shaderProgram);

        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        projMatrix.get(fb);
        glUniformMatrix4fv(uProjLoc, false, fb);

        fb.clear();
        viewMatrix.get(fb);
        glUniformMatrix4fv(uViewLoc, false, fb);

        glUniform3f(uCameraPosLoc, cameraPos.x, cameraPos.y, cameraPos.z);
        glUniform1f(uBrightnessLoc, brightness);

        glBindVertexArray(vaoId);

        // Render each of the 7 bands as a triangle strip
        int verticesPerBand = (spokeCount + 1) * 2;
        for (int i = 0; i < 7; i++) {
            glDrawArrays(GL_TRIANGLE_STRIP, i * verticesPerBand, verticesPerBand);
        }

        glBindVertexArray(0);
        glUseProgram(0);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
    }

    public int getSpokeCount() {
        return spokeCount;
    }

    public float getRadius() {
        return radius;
    }

    public int getTotalVertices() {
        return totalVertices;
    }

    @Override
    public void close() {
        if (vboId != 0) glDeleteBuffers(vboId);
        if (vaoId != 0) glDeleteVertexArrays(vaoId);
        if (shaderProgram != 0) glDeleteProgram(shaderProgram);
    }
}
