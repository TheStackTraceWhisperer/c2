package com.ffxi.viewer.shader;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

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
            #version 460 core
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
            #version 460 core
            in vec2 vTexCoord;

            uniform sampler2D uDiffuseTexture;
            uniform float uAlphaThreshold;

            void main() {
                if (uAlphaThreshold > 0.0) {
                    float alpha = clamp(texture(uDiffuseTexture, vTexCoord).a * 2.0, 0.0, 1.0);
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
        depthProgram = GlShaderUtil.createProgram(DEPTH_VERT_SRC, DEPTH_FRAG_SRC, "ShadowMap");

        uLightSpaceLoc = glGetUniformLocation(depthProgram, "uLightSpaceMatrix");
        uModelLoc = glGetUniformLocation(depthProgram, "uModelMatrix");
        uAlphaThresholdLoc = glGetUniformLocation(depthProgram, "uAlphaThreshold");

        glUseProgram(depthProgram);
        glUniform1i(glGetUniformLocation(depthProgram, "uDiffuseTexture"), 0);
        glUseProgram(0);

        fboId = glGenFramebuffers();
        depthTextureId = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, depthTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, SHADOW_WIDTH, SHADOW_HEIGHT, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer borderBuffer = stack.floats(1.0f, 1.0f, 1.0f, 1.0f);
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, borderBuffer);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTextureId, 0);

        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            close();
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
        Vector3f up = Math.abs(normLight.y) > 0.98f
                ? new Vector3f(0f, 0f, 1f)
                : new Vector3f(0f, -1f, 0f);

        float shadowDist = boxSize * 1.2f;
        Vector3f eyePos = new Vector3f(targetPos).add(new Vector3f(normLight).mul(shadowDist));

        lightView.identity().lookAt(eyePos, targetPos, up);
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
        glDisable(GL_CULL_FACE);

        glUseProgram(depthProgram);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(uLightSpaceLoc, false, lightSpaceMatrix.get(stack.mallocFloat(16)));
        }
    }

    public void setModelMatrix(Matrix4f modelMat, float alphaThreshold) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(uModelLoc, false, modelMat.get(stack.mallocFloat(16)));
        }
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
