package com.ffxi.viewer.renderer;

import com.ffxi.viewer.shader.GlShaderUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;

/**
 * Production zone geometry shader supporting vertex colors, alpha cutout/blending,
 * directional sun/moon lighting, diurnal ambient color, percentage-closer shadow mapping,
 * screen-space ambient occlusion (SSAO), and distance fog.
 */
public final class ZoneShader implements AutoCloseable {
    private static final String VERTEX_SHADER = """
            #version 330 core

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform mat4 uModelMatrix;
            uniform mat4 uLightSpaceMatrix;

            layout(location=0) in vec3 position;
            layout(location=1) in vec3 normal;
            layout(location=2) in vec2 textureCoords;
            layout(location=3) in vec4 vertexColor;

            out vec2 frag_textureCoords;
            out vec4 frag_color;
            out vec3 frag_worldNormal;
            out vec4 frag_lightSpacePos;
            out float frag_viewDistance;

            void main() {
                frag_textureCoords = textureCoords;
                frag_color = vertexColor;

                vec4 worldPos = uModelMatrix * vec4(position, 1.0);
                vec4 viewPos = uViewMatrix * worldPos;
                mat3 normalMatrix = transpose(inverse(mat3(uModelMatrix)));
                vec3 n = normalMatrix * normal;
                frag_worldNormal = length(n) > 1e-4 ? normalize(n) : vec3(0.0, 1.0, 0.0);
                frag_lightSpacePos = uLightSpaceMatrix * worldPos;
                frag_viewDistance = length(viewPos.xyz);

                gl_Position = uProjMatrix * viewPos;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core

            uniform sampler2D diffuseTexture;
            uniform sampler2D uShadowMap;
            uniform sampler2D uSsaoMap;
            uniform float discardThreshold;

            uniform vec3 uSunDir;
            uniform vec3 uSunColor;
            uniform vec3 uAmbientColor;
            uniform int uEnableShadows;
            uniform int uEnableSsao;
            uniform vec2 uViewportSize;

            uniform int uEnableFog;
            uniform float uFogNear;
            uniform float uFogFar;
            uniform vec3 uFogColor;

            in vec2 frag_textureCoords;
            in vec4 frag_color;
            in vec3 frag_worldNormal;
            in vec4 frag_lightSpacePos;
            in float frag_viewDistance;

            out vec4 outColor;

            float computeShadowFactor(vec4 lightSpacePos, vec3 N, vec3 L) {
                vec3 projCoords = lightSpacePos.xyz / max(0.0001, lightSpacePos.w);
                projCoords = projCoords * 0.5 + 0.5;
                if (projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0 || projCoords.y < 0.0 || projCoords.y > 1.0) {
                    return 0.0;
                }
                float bias = max(0.0035 * (1.0 - dot(N, L)), 0.0008);
                vec2 texelSize = 1.0 / vec2(textureSize(uShadowMap, 0));
                float shadow = 0.0;
                for (int x = -1; x <= 1; ++x) {
                    for (int y = -1; y <= 1; ++y) {
                        float pcfDepth = texture(uShadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
                        shadow += (projCoords.z - bias > pcfDepth) ? 1.0 : 0.0;
                    }
                }
                return shadow / 9.0;
            }

            void main() {
                vec4 basePixel = texture(diffuseTexture, frag_textureCoords);
                vec3 vCol = (frag_color.r < 0.01 && frag_color.g < 0.01 && frag_color.b < 0.01) ? vec3(0.5) : frag_color.rgb;
                vec4 coloredPixel = vec4(2.0 * vCol * basePixel.rgb, frag_color.a * basePixel.a);
                if (coloredPixel.a < discardThreshold) {
                    discard;
                }

                vec3 N = normalize(frag_worldNormal);
                vec3 L = normalize(uSunDir);
                float ndotl = max(dot(N, L), 0.0);

                float shadow = 0.0;
                if (uEnableShadows != 0) {
                    shadow = computeShadowFactor(frag_lightSpacePos, N, L);
                }

                float ao = 1.0;
                if (uEnableSsao != 0 && uViewportSize.x > 0.0 && uViewportSize.y > 0.0) {
                    vec2 screenUv = clamp(gl_FragCoord.xy / uViewportSize, 0.0, 1.0);
                    ao = texture(uSsaoMap, screenUv).r;
                }

                vec3 lighting = uAmbientColor * ao + uSunColor * ndotl * (1.0 - 0.55 * shadow);
                vec3 litRgb = coloredPixel.rgb * lighting;

                if (uEnableFog != 0 && uFogFar > uFogNear) {
                    float fogFactor = clamp((frag_viewDistance - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
                    litRgb = mix(litRgb, uFogColor, fogFactor);
                }

                outColor = vec4(litRgb, coloredPixel.a);
            }
            """;

    private static final Matrix4f IDENTITY_MATRIX = new Matrix4f();

    private final int programId;
    private final int projectionLocation;
    private final int viewLocation;
    private final int modelLocation;
    private final int lightSpaceLocation;
    private final int discardThresholdLocation;
    private final int sunDirLocation;
    private final int sunColorLocation;
    private final int ambientColorLocation;
    private final int enableShadowsLocation;
    private final int enableSsaoLocation;
    private final int viewportSizeLocation;
    private final int enableFogLocation;
    private final int fogNearLocation;
    private final int fogFarLocation;
    private final int fogColorLocation;

    public ZoneShader() {
        programId = GlShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER, "ZoneShader");

        projectionLocation = glGetUniformLocation(programId, "uProjMatrix");
        viewLocation = glGetUniformLocation(programId, "uViewMatrix");
        modelLocation = glGetUniformLocation(programId, "uModelMatrix");
        lightSpaceLocation = glGetUniformLocation(programId, "uLightSpaceMatrix");
        discardThresholdLocation = glGetUniformLocation(programId, "discardThreshold");

        sunDirLocation = glGetUniformLocation(programId, "uSunDir");
        sunColorLocation = glGetUniformLocation(programId, "uSunColor");
        ambientColorLocation = glGetUniformLocation(programId, "uAmbientColor");
        enableShadowsLocation = glGetUniformLocation(programId, "uEnableShadows");
        enableSsaoLocation = glGetUniformLocation(programId, "uEnableSsao");
        viewportSizeLocation = glGetUniformLocation(programId, "uViewportSize");

        enableFogLocation = glGetUniformLocation(programId, "uEnableFog");
        fogNearLocation = glGetUniformLocation(programId, "uFogNear");
        fogFarLocation = glGetUniformLocation(programId, "uFogFar");
        fogColorLocation = glGetUniformLocation(programId, "uFogColor");

        bind();
        glUniform1i(glGetUniformLocation(programId, "diffuseTexture"), 0);
        glUniform1i(glGetUniformLocation(programId, "uShadowMap"), 2);
        glUniform1i(glGetUniformLocation(programId, "uSsaoMap"), 3);
        glUniform1f(discardThresholdLocation, 0.375f);

        // Sensible defaults for standalone viewers that do not configure environment uniforms
        setLightSpaceMatrix(IDENTITY_MATRIX);
        glUniform3f(sunDirLocation, 0.3f, 0.9f, 0.2f);
        glUniform3f(sunColorLocation, 0.25f, 0.25f, 0.25f);
        glUniform3f(ambientColorLocation, 0.85f, 0.85f, 0.85f);
        glUniform1i(enableShadowsLocation, 0);
        glUniform1i(enableSsaoLocation, 0);
        glUniform2f(viewportSizeLocation, 1280.0f, 720.0f);
        glUniform1i(enableFogLocation, 0);
        glUniform1f(fogNearLocation, 60.0f);
        glUniform1f(fogFarLocation, 350.0f);
        glUniform3f(fogColorLocation, 0.6f, 0.7f, 0.8f);
    }

    public void bind() {
        glUseProgram(programId);
    }

    public void setMatrices(Matrix4f projection, Matrix4f view, Matrix4f model) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(projectionLocation, false, projection.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(viewLocation, false, view.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(modelLocation, false, model.get(stack.mallocFloat(16)));
        }
    }

    public void setLightSpaceMatrix(Matrix4f lightSpace) {
        Matrix4f m = lightSpace != null ? lightSpace : IDENTITY_MATRIX;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(lightSpaceLocation, false, m.get(stack.mallocFloat(16)));
        }
    }

    public void setEnvironment(
            Vector3f sunDir,
            Vector3f sunColor,
            Vector3f ambientColor,
            Matrix4f lightSpaceMatrix,
            boolean enableShadows,
            boolean enableSsao,
            int viewportWidth,
            int viewportHeight
    ) {
        setLightSpaceMatrix(lightSpaceMatrix);
        glUniform3f(sunDirLocation, sunDir.x, sunDir.y, sunDir.z);
        glUniform3f(sunColorLocation, sunColor.x, sunColor.y, sunColor.z);
        glUniform3f(ambientColorLocation, ambientColor.x, ambientColor.y, ambientColor.z);
        glUniform1i(enableShadowsLocation, enableShadows ? 1 : 0);
        glUniform1i(enableSsaoLocation, enableSsao ? 1 : 0);
        glUniform2f(viewportSizeLocation, (float) viewportWidth, (float) viewportHeight);
    }

    public void setFog(boolean enableFog, float fogNear, float fogFar, Vector3f fogColor) {
        glUniform1i(enableFogLocation, enableFog ? 1 : 0);
        glUniform1f(fogNearLocation, fogNear);
        glUniform1f(fogFarLocation, fogFar);
        if (fogColor != null) {
            glUniform3f(fogColorLocation, fogColor.x, fogColor.y, fogColor.z);
        }
    }

    public void setDiscardThreshold(float discardThreshold) {
        glUniform1f(discardThresholdLocation, discardThreshold);
    }

    @Override
    public void close() {
        glDeleteProgram(programId);
    }
}
