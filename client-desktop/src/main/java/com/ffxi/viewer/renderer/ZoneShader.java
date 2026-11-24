package com.ffxi.viewer.renderer;

import com.ffxi.viewer.shader.GlShaderUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniform4f;
import static org.lwjgl.opengl.GL20C.glUniform4fv;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;

/**
 * Production zone geometry shader implementing retail FFXI two-light (sun + moon or indoor fixed)
 * lighting, per-pixel point lights with retail {@code S05} attenuation, optional SSAO/shadows,
 * effect sub-pass modes (water/fountains/streetlamp cones/celestial sky), and linear distance fog.
 */
public final class ZoneShader implements AutoCloseable {
    private static final int MAX_POINT_LIGHTS = 4;

    private static final String VERTEX_SHADER = """
            #version 460 core

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform mat4 uModelMatrix;
            uniform mat4 uLightSpaceMatrix;
            uniform vec2 uTexCoordOffset;

            layout(location=0) in vec3 position;
            layout(location=1) in vec3 normal;
            layout(location=2) in vec2 textureCoords;
            layout(location=3) in vec4 vertexColor;

            out vec2 frag_textureCoords;
            out vec4 frag_color;
            out vec3 frag_worldNormal;
            out vec3 frag_worldPos;
            out vec4 frag_lightSpacePos;
            out float frag_viewDistance;

            void main() {
                frag_textureCoords = textureCoords + uTexCoordOffset;
                frag_color = vertexColor;

                vec4 worldPos = uModelMatrix * vec4(position, 1.0);
                vec4 viewPos = uViewMatrix * worldPos;
                mat3 normalMatrix = transpose(inverse(mat3(uModelMatrix)));
                vec3 n = normalMatrix * normal;
                frag_worldNormal = length(n) > 1e-4 ? normalize(n) : vec3(0.0, -1.0, 0.0);
                frag_worldPos = worldPos.xyz;
                frag_lightSpacePos = uLightSpaceMatrix * worldPos;
                frag_viewDistance = length(viewPos.xyz);

                gl_Position = uProjMatrix * viewPos;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 460 core

            uniform sampler2D diffuseTexture;
            uniform sampler2D uShadowMap;
            uniform sampler2D uSsaoMap;
            uniform float discardThreshold;

            uniform vec3 uLight0Dir;
            uniform vec3 uLight0Color;
            uniform vec3 uLight1Dir;
            uniform vec3 uLight1Color;
            uniform vec3 uAmbientColor;
            uniform int uEnableShadows;
            uniform int uEnableSsao;
            uniform vec2 uViewportSize;

            uniform int uPointLightCount;
            uniform vec4 uPointLightPosRange[4];
            uniform vec4 uPointLightColorIntensity[4];

            uniform int uEnableFog;
            uniform float uFogNear;
            uniform float uFogFar;
            uniform vec3 uFogColor;

            uniform vec3 uCameraWorldPos;
            uniform int uOpaquePass;
            uniform int uEffectMode;
            uniform int uHasTexture;
            uniform int uIgnoreTextureAlpha;
            uniform int uComputeSpecular;
            uniform vec4 uColorFactor;
            uniform vec4 uSpecularColor;

            in vec2 frag_textureCoords;
            in vec4 frag_color;
            in vec3 frag_worldNormal;
            in vec3 frag_worldPos;
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

            vec3 safeNorm(vec3 v, vec3 fallback) {
                float len2 = dot(v, v);
                return len2 > 1e-6 ? v * inversesqrt(len2) : fallback;
            }

            void main() {
                vec4 basePixel = (uEffectMode == 0 || uHasTexture != 0)
                        ? texture(diffuseTexture, frag_textureCoords)
                        : vec4(0.5);
                vec3 N = normalize(frag_worldNormal);
                vec3 L0 = safeNorm(uLight0Dir, vec3(0.0, -1.0, 0.0));
                vec3 L1 = safeNorm(uLight1Dir, vec3(0.0, -1.0, 0.0));

                if (uEffectMode != 0) {
                    if (uIgnoreTextureAlpha != 0) {
                        basePixel.a = 0.5;
                    }
                    vec4 stage0 = clamp(2.0 * frag_color * basePixel, vec4(0.0), vec4(1.0));
                    vec3 effRgb = clamp(2.0 * stage0.rgb * uColorFactor.rgb, 0.0, 1.0);
                    float effA = clamp(4.0 * stage0.a * uColorFactor.a, 0.0, 1.0);
                    if (effA < discardThreshold) {
                        discard;
                    }
                    if (uComputeSpecular != 0) {
                        vec3 toCam = uCameraWorldPos - frag_worldPos;
                        vec3 V = length(toCam) > 1e-4 ? normalize(toCam) : vec3(0.0, -1.0, 0.0);
                        vec3 faceN = dot(N, V) >= 0.0 ? N : -N;
                        float fresnel = pow(1.0 - max(dot(faceN, V), 0.0), 3.0);
                        vec3 skyReflectColor = mix(vec3(0.20, 0.30, 0.38), uFogColor, 0.55);
                        float b0 = dot(uLight0Color, vec3(1.0));
                        float b1 = dot(uLight1Color, vec3(1.0));
                        vec3 specL = (b0 >= b1) ? L0 : L1;
                        vec3 specC = (b0 >= b1) ? uLight0Color : uLight1Color;
                        vec3 halfVec = specL + V;
                        vec3 H = length(halfVec) > 1e-4 ? normalize(halfVec) : faceN;
                        float specPow = pow(max(dot(faceN, H), 0.0), 48.0);
                        vec3 sunSpecColor = specC * specPow * 0.45;
                        vec3 specTint = (uSpecularColor.a > 0.001)
                                ? mix(vec3(1.0), clamp(2.0 * uSpecularColor.rgb, 0.0, 1.0), 0.35)
                                : vec3(1.0);
                        effRgb = clamp(
                                effRgb + (skyReflectColor * (0.12 + 0.45 * fresnel) + sunSpecColor) * specTint * effA,
                                0.0,
                                1.0
                        );
                    }
                    if (uEnableFog != 0 && uFogFar > uFogNear) {
                        float fogFactor = clamp((frag_viewDistance - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
                        vec3 fogTarget = (uEffectMode >= 2) ? vec3(0.0) : uFogColor;
                        effRgb = mix(effRgb, fogTarget, fogFactor);
                    }
                    outColor = vec4(effRgb, effA);
                    return;
                }

                float alpha = clamp(4.0 * frag_color.a * basePixel.a, 0.0, 1.0);
                if (alpha < discardThreshold) {
                    discard;
                }

                float shadow = 0.0;
                if (uEnableShadows != 0) {
                    shadow = computeShadowFactor(frag_lightSpacePos, N, L0);
                }

                float ao = 1.0;
                if (uEnableSsao != 0 && uViewportSize.x > 0.0 && uViewportSize.y > 0.0) {
                    vec2 screenUv = clamp(gl_FragCoord.xy / uViewportSize, 0.0, 1.0);
                    ao = texture(uSsaoMap, screenUv).r;
                }

                vec3 direct = uLight0Color * max(dot(N, L0), 0.0) * (1.0 - 0.55 * shadow)
                            + uLight1Color * max(dot(N, L1), 0.0);

                vec3 plSum = vec3(0.0);
                int count = clamp(uPointLightCount, 0, 4);
                for (int i = 0; i < count; i++) {
                    vec4 pr = uPointLightPosRange[i];
                    vec4 ci = uPointLightColorIntensity[i];
                    if (pr.w <= 0.0 || ci.w <= 0.0) continue;
                    vec3 l = pr.xyz - frag_worldPos;
                    float d2 = dot(l, l);
                    float r2 = pr.w * pr.w;
                    if (d2 >= r2) continue;
                    float q = d2 / r2;
                    float win = (1.0 - q * q) * (1.0 - q * q);
                    plSum += ci.rgb * ci.w * max(dot(N, l * inversesqrt(max(d2, 1e-6))), 0.0) * win / max(d2, 1.0);
                }

                vec3 lit = clamp(frag_color.rgb * (uAmbientColor * ao + direct + plSum), 0.0, 1.0);
                vec3 litRgb = clamp(2.0 * basePixel.rgb * lit, 0.0, 1.0);

                if (uEnableFog != 0 && uFogFar > uFogNear) {
                    float fogFactor = clamp((frag_viewDistance - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
                    litRgb = mix(litRgb, uFogColor, fogFactor);
                }

                float outAlpha = (uOpaquePass != 0) ? 1.0 : alpha;
                outColor = vec4(litRgb, outAlpha);
            }
            """;

    private static final Matrix4f IDENTITY_MATRIX = new Matrix4f();

    private final int programId;
    private final int projectionLocation;
    private final int viewLocation;
    private final int modelLocation;
    private final int lightSpaceLocation;
    private final int discardThresholdLocation;
    private final int light0DirLocation;
    private final int light0ColorLocation;
    private final int light1DirLocation;
    private final int light1ColorLocation;
    private final int ambientColorLocation;
    private final int enableShadowsLocation;
    private final int enableSsaoLocation;
    private final int viewportSizeLocation;
    private final int pointLightCountLocation;
    private final int pointLightPosRangeLocation;
    private final int pointLightColorIntensityLocation;
    private final int enableFogLocation;
    private final int fogNearLocation;
    private final int fogFarLocation;
    private final int fogColorLocation;
    private final int texCoordOffsetLocation;
    private final int cameraWorldPosLocation;
    private final int opaquePassLocation;
    private final int effectModeLocation;
    private final int hasTextureLocation;
    private final int ignoreTextureAlphaLocation;
    private final int computeSpecularLocation;
    private final int colorFactorLocation;
    private final int specularColorLocation;

    public ZoneShader() {
        programId = GlShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER, "ZoneShader");

        projectionLocation = glGetUniformLocation(programId, "uProjMatrix");
        viewLocation = glGetUniformLocation(programId, "uViewMatrix");
        modelLocation = glGetUniformLocation(programId, "uModelMatrix");
        lightSpaceLocation = glGetUniformLocation(programId, "uLightSpaceMatrix");
        discardThresholdLocation = glGetUniformLocation(programId, "discardThreshold");

        light0DirLocation = glGetUniformLocation(programId, "uLight0Dir");
        light0ColorLocation = glGetUniformLocation(programId, "uLight0Color");
        light1DirLocation = glGetUniformLocation(programId, "uLight1Dir");
        light1ColorLocation = glGetUniformLocation(programId, "uLight1Color");
        ambientColorLocation = glGetUniformLocation(programId, "uAmbientColor");
        enableShadowsLocation = glGetUniformLocation(programId, "uEnableShadows");
        enableSsaoLocation = glGetUniformLocation(programId, "uEnableSsao");
        viewportSizeLocation = glGetUniformLocation(programId, "uViewportSize");
        pointLightCountLocation = glGetUniformLocation(programId, "uPointLightCount");
        pointLightPosRangeLocation = glGetUniformLocation(programId, "uPointLightPosRange");
        pointLightColorIntensityLocation = glGetUniformLocation(programId, "uPointLightColorIntensity");

        enableFogLocation = glGetUniformLocation(programId, "uEnableFog");
        fogNearLocation = glGetUniformLocation(programId, "uFogNear");
        fogFarLocation = glGetUniformLocation(programId, "uFogFar");
        fogColorLocation = glGetUniformLocation(programId, "uFogColor");
        texCoordOffsetLocation = glGetUniformLocation(programId, "uTexCoordOffset");
        cameraWorldPosLocation = glGetUniformLocation(programId, "uCameraWorldPos");
        opaquePassLocation = glGetUniformLocation(programId, "uOpaquePass");
        effectModeLocation = glGetUniformLocation(programId, "uEffectMode");
        hasTextureLocation = glGetUniformLocation(programId, "uHasTexture");
        ignoreTextureAlphaLocation = glGetUniformLocation(programId, "uIgnoreTextureAlpha");
        computeSpecularLocation = glGetUniformLocation(programId, "uComputeSpecular");
        colorFactorLocation = glGetUniformLocation(programId, "uColorFactor");
        specularColorLocation = glGetUniformLocation(programId, "uSpecularColor");

        bind();
        glUniform1i(glGetUniformLocation(programId, "diffuseTexture"), 0);
        glUniform1i(glGetUniformLocation(programId, "uShadowMap"), 2);
        glUniform1i(glGetUniformLocation(programId, "uSsaoMap"), 3);
        glUniform1f(discardThresholdLocation, 0.375f);

        setLightSpaceMatrix(IDENTITY_MATRIX);
        glUniform3f(light0DirLocation, 0.0f, -1.0f, 0.0f);
        glUniform3f(light0ColorLocation, 0.25f, 0.25f, 0.25f);
        glUniform3f(light1DirLocation, 0.0f, 1.0f, 0.0f);
        glUniform3f(light1ColorLocation, 0.0f, 0.0f, 0.0f);
        glUniform3f(ambientColorLocation, 0.75f, 0.75f, 0.75f);
        glUniform1i(enableShadowsLocation, 0);
        glUniform1i(enableSsaoLocation, 0);
        glUniform2f(viewportSizeLocation, 1280.0f, 720.0f);
        clearPointLights();
        glUniform1i(enableFogLocation, 0);
        glUniform1f(fogNearLocation, 60.0f);
        glUniform1f(fogFarLocation, 350.0f);
        glUniform3f(fogColorLocation, 0.6f, 0.7f, 0.8f);
        glUniform3f(cameraWorldPosLocation, 0.0f, 0.0f, 0.0f);
        glUniform1i(opaquePassLocation, 0);
        resetSceneryState();
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

    /** Configures single-directional environment uniforms (legacy overload delegating to two-light retail path). */
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
        setRetailEnvironment(
                sunDir,
                sunColor,
                new Vector3f(0.0f, -1.0f, 0.0f),
                new Vector3f(0.0f),
                ambientColor,
                lightSpaceMatrix,
                enableShadows,
                enableSsao,
                viewportWidth,
                viewportHeight
        );
    }

    /** Configures retail two-light environment uniforms (Sun/Indoor light 0 + Moon light 1 + ambient). */
    public void setRetailEnvironment(
            Vector3f light0Dir,
            Vector3f light0Color,
            Vector3f light1Dir,
            Vector3f light1Color,
            Vector3f ambientColor,
            Matrix4f lightSpaceMatrix,
            boolean enableShadows,
            boolean enableSsao,
            int viewportWidth,
            int viewportHeight
    ) {
        setLightSpaceMatrix(lightSpaceMatrix);
        if (light0Dir != null) glUniform3f(light0DirLocation, light0Dir.x, light0Dir.y, light0Dir.z);
        if (light0Color != null) glUniform3f(light0ColorLocation, light0Color.x, light0Color.y, light0Color.z);
        if (light1Dir != null) glUniform3f(light1DirLocation, light1Dir.x, light1Dir.y, light1Dir.z);
        if (light1Color != null) glUniform3f(light1ColorLocation, light1Color.x, light1Color.y, light1Color.z);
        if (ambientColor != null) glUniform3f(ambientColorLocation, ambientColor.x, ambientColor.y, ambientColor.z);
        glUniform1i(enableShadowsLocation, enableShadows ? 1 : 0);
        glUniform1i(enableSsaoLocation, enableSsao ? 1 : 0);
        glUniform2f(viewportSizeLocation, (float) viewportWidth, (float) viewportHeight);
    }

    /** Uploads up to 4 active retail point lights for the current scenery chunk. */
    public void setPointLights(int count, float[] posRange16, float[] colorIntensity16) {
        int clamped = Math.max(0, Math.min(MAX_POINT_LIGHTS, count));
        glUniform1i(pointLightCountLocation, clamped);
        if (clamped > 0 && posRange16 != null && colorIntensity16 != null) {
            glUniform4fv(pointLightPosRangeLocation, posRange16);
            glUniform4fv(pointLightColorIntensityLocation, colorIntensity16);
        }
    }

    public void clearPointLights() {
        glUniform1i(pointLightCountLocation, 0);
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

    public void setCameraWorldPos(Vector3f cameraWorldPos) {
        if (cameraWorldPos != null) {
            glUniform3f(cameraWorldPosLocation, cameraWorldPos.x, cameraWorldPos.y, cameraWorldPos.z);
        }
    }

    public void setOpaquePass(boolean opaquePass) {
        glUniform1i(opaquePassLocation, opaquePass ? 1 : 0);
    }

    public void setTexCoordOffset(float uOffset, float vOffset) {
        glUniform2f(texCoordOffsetLocation, uOffset, vOffset);
    }

    public void setEffectState(
            int effectMode,
            boolean hasTexture,
            boolean ignoreTextureAlpha,
            boolean computeSpecular,
            float r,
            float g,
            float b,
            float a,
            float specR,
            float specG,
            float specB,
            float specA
    ) {
        glUniform1i(effectModeLocation, effectMode);
        glUniform1i(hasTextureLocation, hasTexture ? 1 : 0);
        glUniform1i(ignoreTextureAlphaLocation, ignoreTextureAlpha ? 1 : 0);
        glUniform1i(computeSpecularLocation, computeSpecular ? 1 : 0);
        glUniform4f(colorFactorLocation, r, g, b, a);
        glUniform4f(specularColorLocation, specR, specG, specB, specA);
    }

    public void resetSceneryState() {
        glUniform2f(texCoordOffsetLocation, 0.0f, 0.0f);
        setEffectState(0, true, false, false, 0.5f, 0.5f, 0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Override
    public void close() {
        glDeleteProgram(programId);
    }
}
