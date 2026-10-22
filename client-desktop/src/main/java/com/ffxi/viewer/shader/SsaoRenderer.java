package com.ffxi.viewer.shader;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Screen-Space Ambient Occlusion (SSAO) Renderer.
 * Implements a high-performance SSAO pipeline for FFXI map geometry:
 * 1. Geometry Pass: Outputs view-space normals and linear depth into a G-Buffer FBO.
 * 2. SSAO Pass: Evaluates a 32-sample cosine-weighted hemisphere kernel with a 4x4 noise texture.
 * 3. Blur Pass: Edge-preserving 4x4 bilateral box blur to eliminate sampling noise.
 * Produces contact shadows and ambient darkening in corners, crevices, under buildings, and arches.
 */
public final class SsaoRenderer implements AutoCloseable {

    public static final int KERNEL_SIZE = 32;

    private static final String QUAD_VERT_SRC = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            layout (location = 1) in vec2 aTexCoord;

            out vec2 vTexCoord;

            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String SSAO_FRAG_SRC = """
            #version 330 core
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
                vec3 normal = normalize(texture(gNormal, vTexCoord).xyz);

                vec2 noiseScale = uScreenSize / 4.0;
                vec3 randomVec = normalize(texture(texNoise, vTexCoord * noiseScale).xyz);

                // Gram-Schmidt process to create tangent-to-view space matrix
                vec3 tangent = normalize(randomVec - normal * dot(randomVec, normal));
                vec3 bitangent = cross(normal, tangent);
                mat3 TBN = mat3(tangent, bitangent, normal);

                float occlusion = 0.0;
                for (int i = 0; i < 32; ++i) {
                    vec3 samplePos = TBN * samples[i]; // From tangent to view space
                    samplePos = fragPos + samplePos * uRadius;

                    // Project sample position to screen space UV
                    vec4 offset = vec4(samplePos, 1.0);
                    offset = uProjMatrix * offset;
                    offset.xyz /= offset.w;
                    offset.xyz = offset.xyz * 0.5 + 0.5;

                    float sampleDepth = texture(gDepth, offset.xy).r;
                    vec3 samplePosRecon = reconstructViewPos(offset.xy, sampleDepth);

                    // Range check & accumulate
                    float rangeCheck = smoothstep(0.0, 1.0, uRadius / max(0.001, abs(fragPos.z - samplePosRecon.z)));
                    occlusion += (samplePosRecon.z >= samplePos.z + uBias ? 1.0 : 0.0) * rangeCheck;
                }

                occlusion = 1.0 - (occlusion / 32.0);
                FragColor = clamp(pow(occlusion, uPower), 0.0, 1.0);
            }
            """;

    private static final String BLUR_FRAG_SRC = """
            #version 330 core
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

    private final int ssaoProgram;
    private final int blurProgram;

    private final int quadVaoId;
    private final int quadVboId;

    private final FloatBuffer kernelBuffer = BufferUtils.createFloatBuffer(KERNEL_SIZE * 3);

    public SsaoRenderer(int initialWidth, int initialHeight) {
        this.width = Math.max(640, initialWidth);
        this.height = Math.max(360, initialHeight);

        // 1. Compile SSAO shader
        ssaoProgram = createProgram(QUAD_VERT_SRC, SSAO_FRAG_SRC);
        blurProgram = createProgram(QUAD_VERT_SRC, BLUR_FRAG_SRC);

        // 2. Build full-screen quad VAO
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

        // 3. Generate 32-sample hemisphere kernel
        Random rng = new Random(42);
        for (int i = 0; i < KERNEL_SIZE; i++) {
            float x = rng.nextFloat() * 2.0f - 1.0f;
            float y = rng.nextFloat() * 2.0f - 1.0f;
            float z = rng.nextFloat(); // Hemisphere (+Z)
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 1e-4f) {
                x /= len; y /= len; z /= len;
            }
            float scale = (float) i / (float) KERNEL_SIZE;
            scale = 0.1f + scale * scale * (1.0f - 0.1f); // Accelerate samples closer to origin
            x *= scale; y *= scale; z *= scale;

            kernelBuffer.put(x).put(y).put(z);
        }
        kernelBuffer.flip();

        // 4. Generate 4x4 noise texture
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
        // G-Buffer FBO
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
            throw new IllegalStateException("G-Buffer FBO incomplete");
        }

        // SSAO FBO
        ssaoFboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, ssaoFboId);
        ssaoColorTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, ssaoColorTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, height, 0, GL_RED, GL_UNSIGNED_BYTE, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ssaoColorTextureId, 0);

        // Blur FBO
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
     * Binds G-Buffer FBO to render view-space normals and depth.
     */
    public void beginGeometryPass() {
        glBindFramebuffer(GL_FRAMEBUFFER, gFboId);
        glViewport(0, 0, width, height);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
    }

    /**
     * Evaluates SSAO pass and optional bilateral blur pass.
     */
    public void computeSsao(Matrix4f projMatrix, float radius, float bias, float power, boolean enableBlur) {
        // 1. SSAO Generation Pass
        glBindFramebuffer(GL_FRAMEBUFFER, ssaoFboId);
        glViewport(0, 0, width, height);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_DEPTH_TEST);

        glUseProgram(ssaoProgram);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gNormalTextureId);
        glUniform1i(glGetUniformLocation(ssaoProgram, "gNormal"), 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, gDepthTextureId);
        glUniform1i(glGetUniformLocation(ssaoProgram, "gDepth"), 1);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, noiseTextureId);
        glUniform1i(glGetUniformLocation(ssaoProgram, "texNoise"), 2);

        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        projMatrix.get(fb);
        glUniformMatrix4fv(glGetUniformLocation(ssaoProgram, "uProjMatrix"), false, fb);

        Matrix4f invProj = new Matrix4f(projMatrix).invert();
        fb.clear();
        invProj.get(fb);
        glUniformMatrix4fv(glGetUniformLocation(ssaoProgram, "uInvProjMatrix"), false, fb);

        glUniform3fv(glGetUniformLocation(ssaoProgram, "samples"), kernelBuffer);
        glUniform2f(glGetUniformLocation(ssaoProgram, "uScreenSize"), (float) width, (float) height);
        glUniform1f(glGetUniformLocation(ssaoProgram, "uRadius"), radius);
        glUniform1f(glGetUniformLocation(ssaoProgram, "uBias"), bias);
        glUniform1f(glGetUniformLocation(ssaoProgram, "uPower"), power);

        glBindVertexArray(quadVaoId);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // 2. SSAO Blur Pass
        if (enableBlur) {
            glBindFramebuffer(GL_FRAMEBUFFER, blurFboId);
            glClear(GL_COLOR_BUFFER_BIT);

            glUseProgram(blurProgram);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, ssaoColorTextureId);
            glUniform1i(glGetUniformLocation(blurProgram, "ssaoInput"), 0);
            glUniform2f(glGetUniformLocation(blurProgram, "uTexelSize"), 1.0f / width, 1.0f / height);

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

    private static int createProgram(String vsSrc, String fsSrc) {
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, vsSrc);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Vertex compile error: " + glGetShaderInfoLog(vs));
        }

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, fsSrc);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Fragment compile error: " + glGetShaderInfoLog(fs));
        }

        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Program link error: " + glGetProgramInfoLog(prog));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return prog;
    }

    @Override
    public void close() {
        cleanupTexturesAndFbos();
        if (noiseTextureId != 0) glDeleteTextures(noiseTextureId);
        if (quadVboId != 0) glDeleteBuffers(quadVboId);
        if (quadVaoId != 0) glDeleteVertexArrays(quadVaoId);
        if (ssaoProgram != 0) glDeleteProgram(ssaoProgram);
        if (blurProgram != 0) glDeleteProgram(blurProgram);
    }
}
