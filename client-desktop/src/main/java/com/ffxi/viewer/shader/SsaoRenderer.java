package com.ffxi.viewer.shader;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Screen-Space Ambient Occlusion (SSAO) Renderer.
 * Implements a high-performance SSAO pipeline for FFXI map geometry:
 * 1. Geometry Pass: Outputs view-space normals and depth into a G-Buffer FBO using an dedicated G-buffer shader.
 * 2. SSAO Pass: Evaluates a 32-sample cosine-weighted hemisphere kernel with a 4x4 noise texture.
 * 3. Blur Pass: Edge-preserving 4x4 box blur to eliminate sampling noise.
 */
public final class SsaoRenderer implements AutoCloseable {

    public static final int KERNEL_SIZE = 32;

    private static final String GEOM_VERT_SRC = """
            #version 460 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aNormal;

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform mat4 uModelMatrix;

            out vec3 vViewNormal;

            void main() {
                mat3 normalMatrix = transpose(inverse(mat3(uViewMatrix * uModelMatrix)));
                vec3 n = normalMatrix * aNormal;
                vViewNormal = length(n) > 1e-4 ? normalize(n) : vec3(0.0, 0.0, 1.0);
                gl_Position = uProjMatrix * uViewMatrix * uModelMatrix * vec4(aPos, 1.0);
            }
            """;

    private static final String GEOM_FRAG_SRC = """
            #version 460 core
            in vec3 vViewNormal;
            layout (location = 0) out vec4 gNormalOut;

            void main() {
                float len = length(vViewNormal);
                vec3 n = len > 1e-4 ? (vViewNormal / len) : vec3(0.0, 0.0, 1.0);
                if (!gl_FrontFacing) {
                    n = -n;
                }
                gNormalOut = vec4(n, 1.0);
            }
            """;

    private static final String QUAD_VERT_SRC = """
            #version 460 core
            layout (location = 0) in vec2 aPos;
            layout (location = 1) in vec2 aTexCoord;

            out vec2 vTexCoord;

            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String SSAO_FRAG_SRC = """
            #version 460 core
            in vec2 vTexCoord;
            out float FragColor;

            uniform sampler2D gNormal;
            uniform sampler2D gDepth;
            uniform sampler2D texNoise;

            uniform vec3 samples[32];
            uniform mat4 uProjMatrix;
            uniform mat4 uInvProjMatrix;
            uniform vec2 uScreenSize;
            uniform float uRadius;
            uniform float uBias;
            uniform float uPower;

            vec3 reconstructViewPos(vec2 uv, float depth) {
                float z = depth * 2.0 - 1.0;
                vec4 clip = vec4(uv * 2.0 - 1.0, z, 1.0);
                vec4 view = uInvProjMatrix * clip;
                return view.xyz / view.w;
            }

            void main() {
                float depth = texture(gDepth, vTexCoord).r;
                if (depth >= 0.9999) {
                    FragColor = 1.0;
                    return;
                }

                vec3 fragPos = reconstructViewPos(vTexCoord, depth);
                vec3 rawNormal = texture(gNormal, vTexCoord).xyz;
                float normLen = length(rawNormal);
                if (normLen < 1e-4) {
                    FragColor = 1.0;
                    return;
                }
                vec3 normal = rawNormal / normLen;

                vec2 noiseScale = uScreenSize / 4.0;
                vec3 randomVec = texture(texNoise, vTexCoord * noiseScale).xyz;
                vec3 projTangent = randomVec - normal * dot(randomVec, normal);
                if (dot(projTangent, projTangent) < 1e-5) {
                    projTangent = abs(normal.z) < 0.9
                            ? cross(normal, vec3(0.0, 0.0, 1.0))
                            : cross(normal, vec3(1.0, 0.0, 0.0));
                }
                vec3 tangent = normalize(projTangent);
                vec3 bitangent = cross(normal, tangent);
                mat3 TBN = mat3(tangent, bitangent, normal);

                float occlusion = 0.0;
                for (int i = 0; i < 32; ++i) {
                    vec3 samplePos = TBN * samples[i];
                    samplePos = fragPos + samplePos * uRadius;

                    vec4 offset = vec4(samplePos, 1.0);
                    offset = uProjMatrix * offset;
                    offset.xyz /= offset.w;
                    offset.xyz = offset.xyz * 0.5 + 0.5;

                    float sampleDepth = texture(gDepth, offset.xy).r;
                    vec3 samplePosRecon = reconstructViewPos(offset.xy, sampleDepth);

                    float rangeCheck = smoothstep(0.0, 1.0, uRadius / max(0.001, abs(fragPos.z - samplePosRecon.z)));
                    occlusion += (samplePosRecon.z >= samplePos.z + uBias ? 1.0 : 0.0) * rangeCheck;
                }

                occlusion = 1.0 - (occlusion / 32.0);
                FragColor = clamp(pow(occlusion, uPower), 0.0, 1.0);
            }
            """;

    private static final String BLUR_FRAG_SRC = """
            #version 460 core
            in vec2 vTexCoord;
            out float FragColor;

            uniform sampler2D ssaoInput;
            uniform vec2 uTexelSize;

            void main() {
                float result = 0.0;
                for (int x = -2; x < 2; ++x) {
                    for (int y = -2; y < 2; ++y) {
                        vec2 offset = vec2(float(x), float(y)) * uTexelSize;
                        result += texture(ssaoInput, vTexCoord + offset).r;
                    }
                }
                FragColor = result / 16.0;
            }
            """;

    private int width;
    private int height;

    private int gFboId;
    private int gNormalTextureId;
    private int gDepthTextureId;

    private int ssaoFboId;
    private int ssaoColorTextureId;

    private int blurFboId;
    private int blurColorTextureId;

    private int noiseTextureId;

    private final int geometryProgram;
    private final int ssaoProgram;
    private final int blurProgram;

    // Cached uniform locations
    private final int uGeomProjLoc;
    private final int uGeomViewLoc;
    private final int uGeomModelLoc;

    private final int uSsaoGNormalLoc;
    private final int uSsaoGDepthLoc;
    private final int uSsaoTexNoiseLoc;
    private final int uSsaoProjLoc;
    private final int uSsaoInvProjLoc;
    private final int uSsaoSamplesLoc;
    private final int uSsaoScreenSizeLoc;
    private final int uSsaoRadiusLoc;
    private final int uSsaoBiasLoc;
    private final int uSsaoPowerLoc;

    private final int uBlurInputLoc;
    private final int uBlurTexelSizeLoc;

    private final int quadVaoId;
    private final int quadVboId;

    private final FloatBuffer kernelBuffer = BufferUtils.createFloatBuffer(KERNEL_SIZE * 3);

    public SsaoRenderer(int initialWidth, int initialHeight) {
        this.width = Math.max(640, initialWidth);
        this.height = Math.max(360, initialHeight);

        geometryProgram = GlShaderUtil.createProgram(GEOM_VERT_SRC, GEOM_FRAG_SRC, "SsaoGeometry");
        ssaoProgram = GlShaderUtil.createProgram(QUAD_VERT_SRC, SSAO_FRAG_SRC, "SsaoKernel");
        blurProgram = GlShaderUtil.createProgram(QUAD_VERT_SRC, BLUR_FRAG_SRC, "SsaoBlur");

        uGeomProjLoc = glGetUniformLocation(geometryProgram, "uProjMatrix");
        uGeomViewLoc = glGetUniformLocation(geometryProgram, "uViewMatrix");
        uGeomModelLoc = glGetUniformLocation(geometryProgram, "uModelMatrix");

        uSsaoGNormalLoc = glGetUniformLocation(ssaoProgram, "gNormal");
        uSsaoGDepthLoc = glGetUniformLocation(ssaoProgram, "gDepth");
        uSsaoTexNoiseLoc = glGetUniformLocation(ssaoProgram, "texNoise");
        uSsaoProjLoc = glGetUniformLocation(ssaoProgram, "uProjMatrix");
        uSsaoInvProjLoc = glGetUniformLocation(ssaoProgram, "uInvProjMatrix");
        uSsaoSamplesLoc = glGetUniformLocation(ssaoProgram, "samples");
        uSsaoScreenSizeLoc = glGetUniformLocation(ssaoProgram, "uScreenSize");
        uSsaoRadiusLoc = glGetUniformLocation(ssaoProgram, "uRadius");
        uSsaoBiasLoc = glGetUniformLocation(ssaoProgram, "uBias");
        uSsaoPowerLoc = glGetUniformLocation(ssaoProgram, "uPower");

        uBlurInputLoc = glGetUniformLocation(blurProgram, "ssaoInput");
        uBlurTexelSizeLoc = glGetUniformLocation(blurProgram, "uTexelSize");

        // Build full-screen quad VAO
        float[] quadVertices = {
                -1.0f,  1.0f, 0.0f, 1.0f,
                -1.0f, -1.0f, 0.0f, 0.0f,
                 1.0f,  1.0f, 1.0f, 1.0f,
                 1.0f, -1.0f, 1.0f, 0.0f,
        };
        quadVaoId = glGenVertexArrays();
        quadVboId = glGenBuffers();
        glBindVertexArray(quadVaoId);
        glBindBuffer(GL_ARRAY_BUFFER, quadVboId);
        ByteBuffer qb = BufferUtils.createByteBuffer(quadVertices.length * 4);
        for (float f : quadVertices) qb.putFloat(f);
        qb.flip();
        glBufferData(GL_ARRAY_BUFFER, qb, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);

        // Generate 32-sample hemisphere kernel
        Random rng = new Random(42);
        for (int i = 0; i < KERNEL_SIZE; i++) {
            float x = rng.nextFloat() * 2.0f - 1.0f;
            float y = rng.nextFloat() * 2.0f - 1.0f;
            float z = rng.nextFloat();
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 1e-4f) {
                x /= len; y /= len; z /= len;
            }
            float scale = (float) i / (float) KERNEL_SIZE;
            scale = 0.1f + scale * scale * (1.0f - 0.1f);
            x *= scale; y *= scale; z *= scale;

            kernelBuffer.put(x).put(y).put(z);
        }
        kernelBuffer.flip();

        // Generate 4x4 noise texture
        ByteBuffer noiseBuf = BufferUtils.createByteBuffer(16 * 3 * 4);
        for (int i = 0; i < 16; i++) {
            float nx = rng.nextFloat() * 2.0f - 1.0f;
            float ny = rng.nextFloat() * 2.0f - 1.0f;
            noiseBuf.putFloat(nx).putFloat(ny).putFloat(0.0f);
        }
        noiseBuf.flip();

        noiseTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, noiseTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, 4, 4, 0, GL_RGB, GL_FLOAT, noiseBuf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);

        initFramebuffers();
    }

    private void initFramebuffers() {
        gFboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, gFboId);

        gNormalTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, gNormalTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, gNormalTextureId, 0);

        gDepthTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, gDepthTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, gDepthTextureId, 0);

        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            throw new IllegalStateException("G-Buffer FBO incomplete");
        }

        ssaoFboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, ssaoFboId);
        ssaoColorTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, ssaoColorTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, height, 0, GL_RED, GL_UNSIGNED_BYTE, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ssaoColorTextureId, 0);

        blurFboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, blurFboId);
        blurColorTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, blurColorTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, height, 0, GL_RED, GL_UNSIGNED_BYTE, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, blurColorTextureId, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0 || (newWidth == width && newHeight == height)) {
            return;
        }
        cleanupTexturesAndFbos();
        this.width = newWidth;
        this.height = newHeight;
        initFramebuffers();
    }

    /**
     * Binds G-Buffer FBO and the internal G-buffer geometry shader program.
     */
    public void beginGeometryPass() {
        glBindFramebuffer(GL_FRAMEBUFFER, gFboId);
        glViewport(0, 0, width, height);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glUseProgram(geometryProgram);
    }

    /**
     * Binds G-Buffer FBO and uploads projection & view matrices to the G-buffer geometry shader.
     */
    public void beginGeometryPass(Matrix4f projMatrix, Matrix4f viewMatrix) {
        beginGeometryPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(uGeomProjLoc, false, projMatrix.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(uGeomViewLoc, false, viewMatrix.get(stack.mallocFloat(16)));
        }
    }

    public void setGeometryModelMatrix(Matrix4f modelMatrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(uGeomModelLoc, false, modelMatrix.get(stack.mallocFloat(16)));
        }
    }

    /**
     * Evaluates SSAO pass and optional bilateral blur pass.
     */
    public void computeSsao(Matrix4f projMatrix, float radius, float bias, float power, boolean enableBlur) {
        glBindFramebuffer(GL_FRAMEBUFFER, ssaoFboId);
        glViewport(0, 0, width, height);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_DEPTH_TEST);

        glUseProgram(ssaoProgram);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gNormalTextureId);
        glUniform1i(uSsaoGNormalLoc, 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, gDepthTextureId);
        glUniform1i(uSsaoGDepthLoc, 1);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, noiseTextureId);
        glUniform1i(uSsaoTexNoiseLoc, 2);

        Matrix4f invProj = new Matrix4f(projMatrix).invert();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(uSsaoProjLoc, false, projMatrix.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(uSsaoInvProjLoc, false, invProj.get(stack.mallocFloat(16)));
        }

        glUniform3fv(uSsaoSamplesLoc, kernelBuffer);
        glUniform2f(uSsaoScreenSizeLoc, (float) width, (float) height);
        glUniform1f(uSsaoRadiusLoc, radius);
        glUniform1f(uSsaoBiasLoc, bias);
        glUniform1f(uSsaoPowerLoc, power);

        glBindVertexArray(quadVaoId);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        if (enableBlur) {
            glBindFramebuffer(GL_FRAMEBUFFER, blurFboId);
            glClear(GL_COLOR_BUFFER_BIT);

            glUseProgram(blurProgram);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, ssaoColorTextureId);
            glUniform1i(uBlurInputLoc, 0);
            glUniform2f(uBlurTexelSizeLoc, 1.0f / width, 1.0f / height);

            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }

        glBindVertexArray(0);
        glUseProgram(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glEnable(GL_DEPTH_TEST);
    }

    public int getFinalSsaoTextureId(boolean enableBlur) {
        return enableBlur ? blurColorTextureId : ssaoColorTextureId;
    }

    public int getGDepthTextureId() {
        return gDepthTextureId;
    }

    public int getGNormalTextureId() {
        return gNormalTextureId;
    }

    private void cleanupTexturesAndFbos() {
        if (blurColorTextureId != 0) glDeleteTextures(blurColorTextureId);
        if (blurFboId != 0) glDeleteFramebuffers(blurFboId);

        if (ssaoColorTextureId != 0) glDeleteTextures(ssaoColorTextureId);
        if (ssaoFboId != 0) glDeleteFramebuffers(ssaoFboId);

        if (gDepthTextureId != 0) glDeleteTextures(gDepthTextureId);
        if (gNormalTextureId != 0) glDeleteTextures(gNormalTextureId);
        if (gFboId != 0) glDeleteFramebuffers(gFboId);
    }

    @Override
    public void close() {
        cleanupTexturesAndFbos();
        if (noiseTextureId != 0) glDeleteTextures(noiseTextureId);
        if (quadVboId != 0) glDeleteBuffers(quadVboId);
        if (quadVaoId != 0) glDeleteVertexArrays(quadVaoId);
        if (geometryProgram != 0) glDeleteProgram(geometryProgram);
        if (ssaoProgram != 0) glDeleteProgram(ssaoProgram);
        if (blurProgram != 0) glDeleteProgram(blurProgram);
    }
}
