package com.ffxi.viewer.shader;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Directional Cascaded / Orthographic Shadow Map Renderer.
 * Manages an FBO with a high-precision 32-bit floating point depth texture (2048x2048).
 * Renders shadow casters from the sun/moon celestial directional vector and computes
 * light-space transformation matrices with configurable orthographic bounding boxes,
 * percentage closer filtering (PCF), and depth bias controls.
 */
public final class ShadowMapRenderer implements AutoCloseable {

    public static final int SHADOW_WIDTH = 2048;
    public static final int SHADOW_HEIGHT = 2048;

    private static final String DEPTH_VERT_SRC = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 2) in vec2 aTexCoord;

            uniform mat4 uLightSpaceMatrix;
            uniform mat4 uModelMatrix;

            out vec2 vTexCoord;

            void main() {
                vTexCoord = aTexCoord;
                gl_Position = uLightSpaceMatrix * uModelMatrix * vec4(aPos, 1.0);
            }
            """;

    private static final String DEPTH_FRAG_SRC = """
            #version 330 core
            in vec2 vTexCoord;

            uniform sampler2D uDiffuseTexture;
            uniform float uAlphaThreshold;

            void main() {
                if (uAlphaThreshold > 0.0) {
                    float alpha = texture(uDiffuseTexture, vTexCoord).a;
                    if (alpha < uAlphaThreshold) {
                        discard;
                    }
                }
                // Depth is written automatically
            }
            """;

    private final int fboId;
    private final int depthTextureId;
    private final int depthProgram;
    private final int uLightSpaceLoc;
    private final int uModelLoc;
    private final int uAlphaThresholdLoc;

    private final Matrix4f lightSpaceMatrix = new Matrix4f();
    private final Matrix4f lightProjection = new Matrix4f();
    private final Matrix4f lightView = new Matrix4f();

    public ShadowMapRenderer() {
        // 1. Compile depth pass shader
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, DEPTH_VERT_SRC);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile ShadowMap vertex shader: " + glGetShaderInfoLog(vs));
        }

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, DEPTH_FRAG_SRC);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile ShadowMap fragment shader: " + glGetShaderInfoLog(fs));
        }

        depthProgram = glCreateProgram();
        glAttachShader(depthProgram, vs);
        glAttachShader(depthProgram, fs);
        glLinkProgram(depthProgram);
        if (glGetProgrami(depthProgram, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to link ShadowMap depth program: " + glGetProgramInfoLog(depthProgram));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);

        uLightSpaceLoc = glGetUniformLocation(depthProgram, "uLightSpaceMatrix");
        uModelLoc = glGetUniformLocation(depthProgram, "uModelMatrix");
        uAlphaThresholdLoc = glGetUniformLocation(depthProgram, "uAlphaThreshold");

        // 2. Create Depth FBO and Texture
        fboId = glGenFramebuffers();
        depthTextureId = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, depthTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, SHADOW_WIDTH, SHADOW_HEIGHT, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);

        float[] borderColor = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        FloatBuffer borderBuffer = BufferUtils.createFloatBuffer(4).put(borderColor);
        borderBuffer.flip();
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, borderBuffer);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTextureId, 0);

        // Tell OpenGL we're not drawing to any color buffer
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("ShadowMap Framebuffer incomplete, status: " + status);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Calculates the directional light space matrix (orthographic projection * lookAt).
     */
    public Matrix4f computeLightSpaceMatrix(Vector3f lightDirection, Vector3f targetPos, float boxSize, float nearPlane, float farPlane) {
        Vector3f normLight = new Vector3f(lightDirection).normalize();
        if (Math.abs(normLight.y) > 0.98f) {
            normLight.z += 0.05f;
            normLight.normalize();
        }

        float shadowDist = boxSize * 1.2f;
        Vector3f eyePos = new Vector3f(targetPos).add(new Vector3f(normLight).mul(shadowDist));

        lightView.identity().lookAt(eyePos, targetPos, new Vector3f(0f, 1f, 0f));
        lightProjection.identity().ortho(-boxSize, boxSize, -boxSize, boxSize, nearPlane, farPlane);

        lightSpaceMatrix.identity();
        lightProjection.mul(lightView, lightSpaceMatrix);
        return lightSpaceMatrix;
    }

    /**
     * Binds the shadow map FBO and sets up viewport for depth pass rendering.
     */
    public void beginShadowPass() {
        glViewport(0, 0, SHADOW_WIDTH, SHADOW_HEIGHT);
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDisable(GL_CULL_FACE); // Allow double-sided shadow casting for thin foliage/geometry

        glUseProgram(depthProgram);

        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        lightSpaceMatrix.get(fb);
        glUniformMatrix4fv(uLightSpaceLoc, false, fb);
    }

    public void setModelMatrix(Matrix4f modelMat, float alphaThreshold) {
        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        modelMat.get(fb);
        glUniformMatrix4fv(uModelLoc, false, fb);
        glUniform1f(uAlphaThresholdLoc, alphaThreshold);
    }

    /**
     * Unbinds the shadow map FBO and restores default framebuffer binding.
     */
    public void endShadowPass(int screenWidth, int screenHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenWidth, screenHeight);
        glEnable(GL_CULL_FACE);
        glUseProgram(0);
    }

    public int getDepthTextureId() {
        return depthTextureId;
    }

    public Matrix4f getLightSpaceMatrix() {
        return lightSpaceMatrix;
    }

    @Override
    public void close() {
        if (fboId != 0) glDeleteFramebuffers(fboId);
        if (depthTextureId != 0) glDeleteTextures(depthTextureId);
        if (depthProgram != 0) glDeleteProgram(depthProgram);
    }
}
