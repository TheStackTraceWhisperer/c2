package com.ffxi.viewer.renderer;

import com.ffxi.viewer.shader.GlShaderUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glBufferSubData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniform4f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL31C.glUniformBlockBinding;

/**
 * Linear-blend-skinning character &amp; NPC shader supporting retail {@code S2F_Environment}
 * actor lighting (diffuse + floor point lamp + {@code floor(ambient * 128) / 255}), distance fog,
 * and flat-white silhouette shadow pass rendering.
 */
public final class CharacterShader implements AutoCloseable {
    public static final int MAX_JOINTS = 128;

    private static final String VERTEX_SHADER = """
            #version 460 core

            layout (std140) uniform JointMatrices {
                mat4 joints[128];
            };

            uniform mat4 uProjMatrix;
            uniform mat4 uViewMatrix;
            uniform mat4 uModelMatrix;

            layout(location=0) in vec3 position0;
            layout(location=1) in vec3 position1;
            layout(location=2) in vec3 normal0;
            layout(location=3) in vec3 normal1;
            layout(location=4) in vec2 textureCoords;
            layout(location=5) in float jointWeight;
            layout(location=6) in uint joint0;
            layout(location=7) in uint joint1;
            layout(location=8) in vec4 vertexColor;

            out vec2 frag_textureCoords;
            out vec4 frag_cameraSpacePos;
            out vec4 frag_color;
            out vec3 frag_worldNormal;
            out vec3 frag_worldPos;
            out float frag_viewDistance;

            void main() {
                frag_textureCoords = textureCoords;

                mat3 invTransModel = transpose(inverse(mat3(uModelMatrix)));
                vec4 skinnedNormal = jointWeight * (joints[int(joint0)] * vec4(normal0, 0.0))
                                   + (1.0 - jointWeight) * (joints[int(joint1)] * vec4(normal1, 0.0));
                vec3 sn = length(skinnedNormal.xyz) > 1e-4 ? normalize(skinnedNormal.xyz) : vec3(0.0, -1.0, 0.0);
                vec3 wn = invTransModel * sn;
                frag_worldNormal = length(wn) > 1e-4 ? normalize(wn) : vec3(0.0, -1.0, 0.0);

                vec4 modelSpacePos = (joints[int(joint0)] * vec4(position0, jointWeight))
                                   + (joints[int(joint1)] * vec4(position1, 1.0 - jointWeight));
                vec4 worldSpacePos = uModelMatrix * modelSpacePos;
                vec4 camSpacePos = uViewMatrix * worldSpacePos;

                frag_color = vertexColor;
                frag_worldPos = worldSpacePos.xyz;
                frag_cameraSpacePos = camSpacePos;
                frag_viewDistance = length(camSpacePos.xyz);
                gl_Position = uProjMatrix * camSpacePos;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 460 core

            uniform sampler2D diffuseTexture;
            uniform float discardThreshold;

            uniform int uSilhouetteMode;
            uniform int uEnableLighting;
            uniform vec3 uActorLightDir;
            uniform vec3 uActorLightColor;
            uniform vec3 uActorAmbientColor;
            uniform vec4 uActorLampPosRange;
            uniform vec4 uActorLampColorIntensity;

            uniform int uEnableFog;
            uniform float uFogNear;
            uniform float uFogFar;
            uniform vec3 uFogColor;

            in vec2 frag_textureCoords;
            in vec4 frag_color;
            in vec3 frag_worldNormal;
            in vec3 frag_worldPos;
            in float frag_viewDistance;

            out vec4 outColor;

            void main() {
                vec4 basePixel = texture(diffuseTexture, frag_textureCoords);
                if (basePixel.a < discardThreshold) {
                    discard;
                }
                if (uSilhouetteMode != 0) {
                    outColor = vec4(1.0);
                    return;
                }

                vec3 rgb = basePixel.rgb;
                if (uEnableLighting != 0) {
                    vec3 N = normalize(frag_worldNormal);
                    vec3 L = length(uActorLightDir) > 1e-4 ? normalize(uActorLightDir) : vec3(0.0, -1.0, 0.0);
                    float ndl = max(dot(N, L), 0.0);

                    vec3 point = vec3(0.0);
                    if (uActorLampPosRange.w > 0.0 && uActorLampColorIntensity.w > 0.0) {
                        vec3 l = uActorLampPosRange.xyz - frag_worldPos;
                        float d2 = dot(l, l);
                        if (d2 < uActorLampPosRange.w * uActorLampPosRange.w) {
                            float att2 = 1.0 / max(uActorLampColorIntensity.w, 1e-4);
                            float wt = 1.0 / max(d2 * att2, 1e-4);
                            point = uActorLampColorIntensity.rgb * wt * max(dot(N, l * inversesqrt(max(d2, 1e-12))), 0.0);
                        }
                    }

                    vec3 amb = floor(uActorAmbientColor * 128.0) / 255.0;
                    vec3 lit = clamp(amb + 0.5 * (uActorLightColor * ndl + point), 0.0, 1.0);
                    rgb = clamp(2.0 * basePixel.rgb * lit, 0.0, 1.0);
                }

                if (uEnableFog != 0 && uFogFar > uFogNear) {
                    float fogFactor = clamp((frag_viewDistance - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
                    rgb = mix(rgb, uFogColor, fogFactor);
                }

                outColor = vec4(rgb, basePixel.a);
            }
            """;

    private final int programId;
    private final int jointBufferId;
    private final int projectionLocation;
    private final int viewLocation;
    private final int modelLocation;
    private final int discardThresholdLocation;
    private final int silhouetteModeLocation;
    private final int enableLightingLocation;
    private final int actorLightDirLocation;
    private final int actorLightColorLocation;
    private final int actorAmbientColorLocation;
    private final int actorLampPosRangeLocation;
    private final int actorLampColorIntensityLocation;
    private final int enableFogLocation;
    private final int fogNearLocation;
    private final int fogFarLocation;
    private final int fogColorLocation;
    private final FloatBuffer jointUploadBuffer = BufferUtils.createFloatBuffer(MAX_JOINTS * 16);

    public CharacterShader() {
        programId = GlShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER, "CharacterShader");

        projectionLocation = glGetUniformLocation(programId, "uProjMatrix");
        viewLocation = glGetUniformLocation(programId, "uViewMatrix");
        modelLocation = glGetUniformLocation(programId, "uModelMatrix");
        discardThresholdLocation = glGetUniformLocation(programId, "discardThreshold");

        silhouetteModeLocation = glGetUniformLocation(programId, "uSilhouetteMode");
        enableLightingLocation = glGetUniformLocation(programId, "uEnableLighting");
        actorLightDirLocation = glGetUniformLocation(programId, "uActorLightDir");
        actorLightColorLocation = glGetUniformLocation(programId, "uActorLightColor");
        actorAmbientColorLocation = glGetUniformLocation(programId, "uActorAmbientColor");
        actorLampPosRangeLocation = glGetUniformLocation(programId, "uActorLampPosRange");
        actorLampColorIntensityLocation = glGetUniformLocation(programId, "uActorLampColorIntensity");
        enableFogLocation = glGetUniformLocation(programId, "uEnableFog");
        fogNearLocation = glGetUniformLocation(programId, "uFogNear");
        fogFarLocation = glGetUniformLocation(programId, "uFogFar");
        fogColorLocation = glGetUniformLocation(programId, "uFogColor");

        jointBufferId = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, jointBufferId);
        glBufferData(GL_UNIFORM_BUFFER, MAX_JOINTS * 16L * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);

        int blockIndex = glGetUniformBlockIndex(programId, "JointMatrices");
        glUniformBlockBinding(programId, blockIndex, 0);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, jointBufferId);

        bind();
        glUniform1i(glGetUniformLocation(programId, "diffuseTexture"), 0);
        glUniform1f(discardThresholdLocation, 0.375f);
        glUniform1i(silhouetteModeLocation, 0);
        glUniform1i(enableLightingLocation, 0);
        glUniform3f(actorLightDirLocation, 0.0f, -1.0f, 0.0f);
        glUniform3f(actorLightColorLocation, 1.0f, 1.0f, 1.0f);
        glUniform3f(actorAmbientColorLocation, 0.5f, 0.5f, 0.5f);
        glUniform4f(actorLampPosRangeLocation, 0.0f, 0.0f, 0.0f, 0.0f);
        glUniform4f(actorLampColorIntensityLocation, 0.0f, 0.0f, 0.0f, 0.0f);
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

    public void setDiscardThreshold(float discardThreshold) {
        glUniform1f(discardThresholdLocation, discardThreshold);
    }

    public void setSilhouetteMode(boolean silhouette) {
        glUniform1i(silhouetteModeLocation, silhouette ? 1 : 0);
    }

    public void setActorLighting(
            boolean enabled,
            Vector3f lightDir,
            Vector3f lightColor,
            Vector3f ambientColor,
            Vector3f lampPos,
            float lampRange,
            Vector3f lampColor,
            float lampIntensity
    ) {
        glUniform1i(enableLightingLocation, enabled ? 1 : 0);
        if (!enabled) {
            return;
        }
        if (lightDir != null) glUniform3f(actorLightDirLocation, lightDir.x, lightDir.y, lightDir.z);
        if (lightColor != null) glUniform3f(actorLightColorLocation, lightColor.x, lightColor.y, lightColor.z);
        if (ambientColor != null) glUniform3f(actorAmbientColorLocation, ambientColor.x, ambientColor.y, ambientColor.z);
        if (lampPos != null && lampColor != null && lampRange > 0.0f && lampIntensity > 0.0f) {
            glUniform4f(actorLampPosRangeLocation, lampPos.x, lampPos.y, lampPos.z, lampRange);
            glUniform4f(actorLampColorIntensityLocation, lampColor.x, lampColor.y, lampColor.z, lampIntensity);
        } else {
            glUniform4f(actorLampPosRangeLocation, 0.0f, 0.0f, 0.0f, 0.0f);
            glUniform4f(actorLampColorIntensityLocation, 0.0f, 0.0f, 0.0f, 0.0f);
        }
    }

    public void setFog(boolean enabled, float fogNear, float fogFar, Vector3f fogColor) {
        glUniform1i(enableFogLocation, enabled ? 1 : 0);
        glUniform1f(fogNearLocation, fogNear);
        glUniform1f(fogFarLocation, fogFar);
        if (fogColor != null) {
            glUniform3f(fogColorLocation, fogColor.x, fogColor.y, fogColor.z);
        }
    }

    public void uploadJointMatrices(Matrix4f[] joints) {
        if (joints.length > MAX_JOINTS) {
            throw new IllegalArgumentException("Character has " + joints.length + " joints, but shader supports " + MAX_JOINTS);
        }

        jointUploadBuffer.clear();
        for (int i = 0; i < joints.length; i++) {
            joints[i].get(i * 16, jointUploadBuffer);
        }
        jointUploadBuffer.position(joints.length * 16);
        jointUploadBuffer.flip();

        glBindBuffer(GL_UNIFORM_BUFFER, jointBufferId);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, jointUploadBuffer);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    @Override
    public void close() {
        glDeleteBuffers(jointBufferId);
        glDeleteProgram(programId);
    }
}
