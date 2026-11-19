package com.ffxi.viewer.shader;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Advanced Interactive Map Geometry Shader for the Shader Sandbox.
 * Combines:
 * - Dual celestial directional lighting (Sun + Moon with authentic XIM diurnal color mixing)
 * - Directional Shadow Mapping with 3x3 percentage closer filtering (PCF)
 * - Tangent-Space Bump / Normal Mapping with procedural or retail Section 0x5D normal maps
 * - Screen-Space Ambient Occlusion (SSAO) ambient term modulation
 * - Elemental atmospheric distance fog
 * - Double-sided lighting fallback for cutouts and foliage
 * - Interactive Debug View Modes (Normal Lit, Diffuse Only, Normal Vectors, Shadows Only, SSAO Only, Lighting Only)
 */
public final class SandboxMapShader implements AutoCloseable {

    private static final String VERTEX_SHADER_SRC = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;
            layout (location = 3) in vec4 aColor;

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform mat4 uModelMatrix;
            uniform mat4 uLightSpaceMatrix;

            out vec3 vWorldPos;
            out vec3 vNormal;
            out vec2 vTexCoord;
            out vec4 vColor;
            out vec4 vFragPosLightSpace;

            void main() {
                vec4 worldPos = uModelMatrix * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                mat3 invTranspose = transpose(inverse(mat3(uModelMatrix)));
                vNormal = normalize(invTranspose * aNormal);
                vTexCoord = aTexCoord;
                vColor = aColor;

                vFragPosLightSpace = uLightSpaceMatrix * worldPos;
                gl_Position = uProjMatrix * uViewMatrix * worldPos;
            }
            """;

    private static final String FRAGMENT_SHADER_SRC = """
            #version 330 core
            in vec3 vWorldPos;
            in vec3 vNormal;
            in vec2 vTexCoord;
            in vec4 vColor;
            in vec4 vFragPosLightSpace;

            out vec4 FragColor;

            // Texture units
            uniform sampler2D uDiffuseTexture; // Unit 0
            uniform sampler2D uNormalTexture;  // Unit 1
            uniform sampler2D uShadowMap;      // Unit 2
            uniform sampler2D uSsaoTexture;    // Unit 3

            // Screen & View
            uniform vec3 uCameraPos;
            uniform vec2 uScreenSize;
            uniform float uDiscardThreshold;

            // Celestial Lighting (Sun + Moon)
            uniform vec3 uSunDir;
            uniform vec3 uSunColor;
            uniform vec3 uMoonDir;
            uniform vec3 uMoonColor;
            uniform vec3 uAmbientColor;
            uniform float uDiffuseMultiplier;

            // Feature Toggles & Controls
            uniform float uEnableNormalMap;
            uniform float uNormalStrength;
            uniform float uEnableShadows;
            uniform float uShadowIntensity;
            uniform float uShadowBiasMin;
            uniform float uShadowBiasMax;
            uniform float uEnableSsao;
            uniform float uSsaoIntensity;
            uniform int uDebugViewMode;

            // Distance Fog
            uniform int uFogEnabled;
            uniform float uFogNear;
            uniform float uFogFar;
            uniform vec3 uFogColor;

            // Perturb normal using screen-space partial derivatives (Christian Schüler method)
            vec3 perturbNormal(vec3 normal, vec3 worldPos, vec2 uv, float strength) {
                vec3 dPdx = dFdx(worldPos);
                vec3 dPdy = dFdy(worldPos);
                vec2 dUdx = dFdx(uv);
                vec2 dUdy = dFdy(uv);

                vec3 N = normalize(normal);
                vec3 T = dPdx * dUdy.t - dPdy * dUdx.t;
                vec3 B = dPdy * dUdx.s - dPdx * dUdy.s;

                float invMax = inversesqrt(max(dot(T, T), dot(B, B)));
                mat3 TBN = mat3(T * invMax, B * invMax, N);

                vec3 mapNormal = texture(uNormalTexture, uv).rgb * 2.0 - 1.0;
                mapNormal.xy *= strength;
                return normalize(TBN * mapNormal);
            }

            // Percentage-Closer Filtered (PCF) 3x3 directional shadow evaluation
            float calculateShadow(vec4 fragPosLightSpace, vec3 normal, vec3 lightDir) {
                vec3 projCoords = fragPosLightSpace.xyz / fragPosLightSpace.w;
                projCoords = projCoords * 0.5 + 0.5;

                // Outside shadow map frustum -> no shadow
                if (projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0 || projCoords.y < 0.0 || projCoords.y > 1.0) {
                    return 0.0;
                }

                float currentDepth = projCoords.z;
                float bias = max(uShadowBiasMax * (1.0 - dot(normal, lightDir)), uShadowBiasMin);

                float shadow = 0.0;
                vec2 texelSize = 1.0 / textureSize(uShadowMap, 0);
                for(int x = -1; x <= 1; ++x) {
                    for(int y = -1; y <= 1; ++y) {
                        float pcfDepth = texture(uShadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
                        shadow += (currentDepth - bias > pcfDepth) ? 1.0 : 0.0;
                    }
                }
                return shadow / 9.0;
            }

            void main() {
                vec4 basePixel = texture(uDiffuseTexture, vTexCoord);

                // Alpha discard threshold for cutout vegetation, banners, fences
                if (uDiscardThreshold > 0.0 && basePixel.a < uDiscardThreshold) {
                    discard;
                }

                // Normal evaluation
                vec3 normal = normalize(vNormal);
                if (uEnableNormalMap > 0.5) {
                    normal = perturbNormal(normal, vWorldPos, vTexCoord, uNormalStrength);
                }

                // Debug view: Normal vectors
                if (uDebugViewMode == 2) {
                    FragColor = vec4(normal * 0.5 + 0.5, 1.0);
                    return;
                }

                // Dual celestial diffuse lighting (Sun + Moon)
                vec3 normSunDir = normalize(uSunDir);
                float sunDiff = max(dot(normal, normSunDir), 0.0);
                // Double-sided lighting fallback for paper-thin geometry
                sunDiff = max(sunDiff, max(dot(-normal, normSunDir) * 0.35, 0.0));

                vec3 normMoonDir = normalize(uMoonDir);
                float moonDiff = max(dot(normal, normMoonDir), 0.0);
                moonDiff = max(moonDiff, max(dot(-normal, normMoonDir) * 0.35, 0.0));

                vec3 diffuseSun = sunDiff * uSunColor * uDiffuseMultiplier;
                vec3 diffuseMoon = moonDiff * uMoonColor * uDiffuseMultiplier;

                // Shadow calculation
                float shadow = 0.0;
                if (uEnableShadows > 0.5) {
                    shadow = calculateShadow(vFragPosLightSpace, normal, normSunDir);
                }

                // Debug view: Shadow map only
                if (uDebugViewMode == 3) {
                    float sVis = 1.0 - shadow * uShadowIntensity;
                    FragColor = vec4(vec3(sVis), 1.0);
                    return;
                }

                // SSAO sampling
                vec2 screenUv = gl_FragCoord.xy / uScreenSize;
                float ssaoFactor = 1.0;
                if (uEnableSsao > 0.5) {
                    float rawSsao = texture(uSsaoTexture, screenUv).r;
                    ssaoFactor = mix(1.0, rawSsao, uSsaoIntensity);
                }

                // Debug view: SSAO only
                if (uDebugViewMode == 4) {
                    FragColor = vec4(vec3(ssaoFactor), 1.0);
                    return;
                }

                // Ambient lighting modulated by SSAO
                vec3 ambient = uAmbientColor * ssaoFactor;

                // Combined total lighting
                vec3 directLight = (1.0 - shadow * uShadowIntensity) * diffuseSun + diffuseMoon;
                vec3 totalLighting = ambient + directLight;

                // Debug view: Lighting only
                if (uDebugViewMode == 5) {
                    FragColor = vec4(totalLighting, 1.0);
                    return;
                }

                // Debug view: Diffuse texture only
                if (uDebugViewMode == 1) {
                    FragColor = vec4(basePixel.rgb, 1.0);
                    return;
                }

                // Authentic FFXI PS2/DirectX 8 material formula: 2.0x RGB blend, 4.0x Alpha
                vec3 finalRgb = 2.0 * basePixel.rgb * vColor.rgb * totalLighting;

                // Atmospheric Distance Fog
                if (uFogEnabled == 1) {
                    float dist = length(vWorldPos - uCameraPos);
                    float fogFactor = clamp((dist - uFogNear) / max(0.001, (uFogFar - uFogNear)), 0.0, 1.0);
                    finalRgb = mix(finalRgb, uFogColor, fogFactor);
                }

                FragColor = vec4(finalRgb, clamp(4.0 * basePixel.a * vColor.a, 0.0, 1.0));
            }
            """;

    private final int programId;

    // Uniform locations
    private final int uProjLoc;
    private final int uViewLoc;
    private final int uModelLoc;
    private final int uLightSpaceLoc;
    private final int uCameraPosLoc;
    private final int uScreenSizeLoc;
    private final int uDiscardThresholdLoc;

    private final int uSunDirLoc;
    private final int uSunColorLoc;
    private final int uMoonDirLoc;
    private final int uMoonColorLoc;
    private final int uAmbientColorLoc;
    private final int uDiffuseMultiplierLoc;

    private final int uEnableNormalMapLoc;
    private final int uNormalStrengthLoc;
    private final int uEnableShadowsLoc;
    private final int uShadowIntensityLoc;
    private final int uShadowBiasMinLoc;
    private final int uShadowBiasMaxLoc;
    private final int uEnableSsaoLoc;
    private final int uSsaoIntensityLoc;
    private final int uDebugViewModeLoc;

    private final int uFogEnabledLoc;
    private final int uFogNearLoc;
    private final int uFogFarLoc;
    private final int uFogColorLoc;

    public SandboxMapShader() {
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, VERTEX_SHADER_SRC);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile SandboxMap vertex shader: " + glGetShaderInfoLog(vs));
        }

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, FRAGMENT_SHADER_SRC);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to compile SandboxMap fragment shader: " + glGetShaderInfoLog(fs));
        }

        programId = glCreateProgram();
        glAttachShader(programId, vs);
        glAttachShader(programId, fs);
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Failed to link SandboxMap program: " + glGetProgramInfoLog(programId));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);

        uProjLoc = glGetUniformLocation(programId, "uProjMatrix");
        uViewLoc = glGetUniformLocation(programId, "uViewMatrix");
        uModelLoc = glGetUniformLocation(programId, "uModelMatrix");
        uLightSpaceLoc = glGetUniformLocation(programId, "uLightSpaceMatrix");
        uCameraPosLoc = glGetUniformLocation(programId, "uCameraPos");
        uScreenSizeLoc = glGetUniformLocation(programId, "uScreenSize");
        uDiscardThresholdLoc = glGetUniformLocation(programId, "uDiscardThreshold");

        uSunDirLoc = glGetUniformLocation(programId, "uSunDir");
        uSunColorLoc = glGetUniformLocation(programId, "uSunColor");
        uMoonDirLoc = glGetUniformLocation(programId, "uMoonDir");
        uMoonColorLoc = glGetUniformLocation(programId, "uMoonColor");
        uAmbientColorLoc = glGetUniformLocation(programId, "uAmbientColor");
        uDiffuseMultiplierLoc = glGetUniformLocation(programId, "uDiffuseMultiplier");

        uEnableNormalMapLoc = glGetUniformLocation(programId, "uEnableNormalMap");
        uNormalStrengthLoc = glGetUniformLocation(programId, "uNormalStrength");
        uEnableShadowsLoc = glGetUniformLocation(programId, "uEnableShadows");
        uShadowIntensityLoc = glGetUniformLocation(programId, "uShadowIntensity");
        uShadowBiasMinLoc = glGetUniformLocation(programId, "uShadowBiasMin");
        uShadowBiasMaxLoc = glGetUniformLocation(programId, "uShadowBiasMax");
        uEnableSsaoLoc = glGetUniformLocation(programId, "uEnableSsao");
        uSsaoIntensityLoc = glGetUniformLocation(programId, "uSsaoIntensity");
        uDebugViewModeLoc = glGetUniformLocation(programId, "uDebugViewMode");

        uFogEnabledLoc = glGetUniformLocation(programId, "uFogEnabled");
        uFogNearLoc = glGetUniformLocation(programId, "uFogNear");
        uFogFarLoc = glGetUniformLocation(programId, "uFogFar");
        uFogColorLoc = glGetUniformLocation(programId, "uFogColor");

        // Bind texture sampler units once
        glUseProgram(programId);
        glUniform1i(glGetUniformLocation(programId, "uDiffuseTexture"), 0);
        glUniform1i(glGetUniformLocation(programId, "uNormalTexture"), 1);
        glUniform1i(glGetUniformLocation(programId, "uShadowMap"), 2);
        glUniform1i(glGetUniformLocation(programId, "uSsaoTexture"), 3);
        glUseProgram(0);
    }

    public void bind() {
        glUseProgram(programId);
    }

    public void unbind() {
        glUseProgram(0);
    }

    public void setTransforms(Matrix4f proj, Matrix4f view, Matrix4f model, Matrix4f lightSpace) {
        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        proj.get(fb);
        glUniformMatrix4fv(uProjLoc, false, fb);

        fb.clear();
        view.get(fb);
        glUniformMatrix4fv(uViewLoc, false, fb);

        fb.clear();
        model.get(fb);
        glUniformMatrix4fv(uModelLoc, false, fb);

        fb.clear();
        lightSpace.get(fb);
        glUniformMatrix4fv(uLightSpaceLoc, false, fb);
    }

    public void setCameraAndScreen(Vector3f camPos, int screenW, int screenH, float discardThreshold) {
        glUniform3f(uCameraPosLoc, camPos.x, camPos.y, camPos.z);
        glUniform2f(uScreenSizeLoc, (float) screenW, (float) screenH);
        glUniform1f(uDiscardThresholdLoc, discardThreshold);
    }

    public void setLighting(Vector3f sunDir, Vector3f sunCol, Vector3f moonDir, Vector3f moonCol,
                            Vector3f ambientCol, float diffuseMult) {
        glUniform3f(uSunDirLoc, sunDir.x, sunDir.y, sunDir.z);
        glUniform3f(uSunColorLoc, sunCol.x, sunCol.y, sunCol.z);
        glUniform3f(uMoonDirLoc, moonDir.x, moonDir.y, moonDir.z);
        glUniform3f(uMoonColorLoc, moonCol.x, moonCol.y, moonCol.z);
        glUniform3f(uAmbientColorLoc, ambientCol.x, ambientCol.y, ambientCol.z);
        glUniform1f(uDiffuseMultiplierLoc, diffuseMult);
    }

    public void setFeatureControls(boolean normalMap, float normalStrength,
                                   boolean shadows, float shadowIntensity, float biasMin, float biasMax,
                                   boolean ssao, float ssaoIntensity, int debugViewMode) {
        glUniform1f(uEnableNormalMapLoc, normalMap ? 1.0f : 0.0f);
        glUniform1f(uNormalStrengthLoc, normalStrength);
        glUniform1f(uEnableShadowsLoc, shadows ? 1.0f : 0.0f);
        glUniform1f(uShadowIntensityLoc, shadowIntensity);
        glUniform1f(uShadowBiasMinLoc, biasMin);
        glUniform1f(uShadowBiasMaxLoc, biasMax);
        glUniform1f(uEnableSsaoLoc, ssao ? 1.0f : 0.0f);
        glUniform1f(uSsaoIntensityLoc, ssaoIntensity);
        glUniform1i(uDebugViewModeLoc, debugViewMode);
    }

    public void setFog(boolean enabled, float near, float far, Vector3f color) {
        glUniform1i(uFogEnabledLoc, enabled ? 1 : 0);
        glUniform1f(uFogNearLoc, near);
        glUniform1f(uFogFarLoc, far);
        glUniform3f(uFogColorLoc, color.x, color.y, color.z);
    }

    @Override
    public void close() {
        if (programId != 0) {
            glDeleteProgram(programId);
        }
    }
}
