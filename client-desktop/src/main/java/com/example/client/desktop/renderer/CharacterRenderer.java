package com.example.client.desktop.renderer;

import com.example.asset.environment.DiurnalLighting;
import com.example.asset.geometry.Matrix4f;
import com.example.asset.skeleton.CharacterMeshGenerator;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.SkinnedMesh;
import com.example.asset.skeleton.SkinnedSubMesh;
import com.example.asset.skeleton.SkinnedVertex;
import com.example.client.desktop.util.MatrixMath;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * High-performance 3D OpenGL hardware skinned mesh renderer for player avatars, remote players, and NPCs.
 * Evaluates GPU linear blend skinning (LBS) driven by skeletal animation poses,
 * matching atmospheric diurnal lighting and distance fog.
 */
@Slf4j
public class CharacterRenderer {

    public static final int MAX_BONES = 32;

    private static final String VERTEX_SHADER_SRC = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;
            layout (location = 3) in vec4 aColor;
            layout (location = 4) in ivec2 aJoints;
            layout (location = 5) in vec2 aWeights;

            uniform mat4 uProjection;
            uniform mat4 uView;
            uniform mat4 uModel;
            uniform mat4 uBones[32];

            out vec3 vNormal;
            out vec2 vTexCoord;
            out vec4 vColor;
            out vec3 vWorldPos;

            void main() {
                mat4 skinMatrix = mat4(0.0);
                float totalWeight = aWeights.x + aWeights.y;
                if (totalWeight > 0.0001) {
                    skinMatrix += uBones[aJoints.x] * aWeights.x;
                    skinMatrix += uBones[aJoints.y] * aWeights.y;
                } else {
                    skinMatrix = mat4(1.0);
                }

                vec4 skinnedPos = skinMatrix * vec4(aPos, 1.0);
                vec4 worldPos = uModel * skinnedPos;
                vWorldPos = worldPos.xyz;

                mat3 normalMatrix = mat3(uModel) * mat3(skinMatrix);
                vNormal = normalMatrix * aNormal;
                vTexCoord = aTexCoord;
                vColor = aColor;

                gl_Position = uProjection * uView * worldPos;
            }
            """;

    private static final String FRAGMENT_SHADER_SRC = """
            #version 330 core
            in vec3 vNormal;
            in vec2 vTexCoord;
            in vec4 vColor;
            in vec3 vWorldPos;

            uniform vec3 uLightDir;
            uniform vec3 uLightColor;
            uniform vec3 uAmbient;
            uniform vec3 uCameraPos;
            uniform float uFogStart;
            uniform float uFogEnd;
            uniform vec3 uFogColor;
            uniform int uFogEnabled;

            out vec4 FragColor;

            void main() {
                vec3 norm = normalize(vNormal);
                vec3 lightDir = normalize(uLightDir);
                float diff = max(dot(norm, lightDir), 0.0);
                // Double-sided fill lighting for thin equipment / cloaks
                diff = max(diff, max(dot(-norm, lightDir) * 0.35, 0.0));

                vec3 lighting = uAmbient + diff * uLightColor;
                vec3 finalRgb = vColor.rgb * lighting;

                if (uFogEnabled == 1) {
                    float dist = length(vWorldPos - uCameraPos);
                    float fogFactor = clamp((dist - uFogStart) / max(0.001, (uFogEnd - uFogStart)), 0.0, 1.0);
                    finalRgb = mix(finalRgb, uFogColor, fogFactor);
                }

                FragColor = vec4(finalRgb, vColor.a);
            }
            """;

    public record GpuCharacterMesh(
            int vaoId,
            int vboId,
            int eboId,
            int indexCount,
            CompositeCharacterModel model
    ) {
        public void cleanup() {
            if (eboId != 0) GL15.glDeleteBuffers(eboId);
            if (vboId != 0) GL15.glDeleteBuffers(vboId);
            if (vaoId != 0) GL30.glDeleteVertexArrays(vaoId);
        }
    }

    private int shaderProgram = 0;
    private int uProjectionLoc = -1;
    private int uViewLoc = -1;
    private int uModelLoc = -1;
    private int uBonesLoc = -1;
    private int uLightDirLoc = -1;
    private int uLightColorLoc = -1;
    private int uAmbientLoc = -1;
    private int uCameraPosLoc = -1;
    private int uFogStartLoc = -1;
    private int uFogEndLoc = -1;
    private int uFogColorLoc = -1;
    private int uFogEnabledLoc = -1;

    private final Map<Short, GpuCharacterMesh> jobModelCache = new HashMap<>();
    private final FloatBuffer bonesBuffer = BufferUtils.createFloatBuffer(MAX_BONES * 16);
    private final float[] identityElements = new Matrix4f().elements();

    public void init() {
        shaderProgram = createShaderProgram(VERTEX_SHADER_SRC, FRAGMENT_SHADER_SRC);

        uProjectionLoc = GL20.glGetUniformLocation(shaderProgram, "uProjection");
        uViewLoc = GL20.glGetUniformLocation(shaderProgram, "uView");
        uModelLoc = GL20.glGetUniformLocation(shaderProgram, "uModel");
        uBonesLoc = GL20.glGetUniformLocation(shaderProgram, "uBones");
        uLightDirLoc = GL20.glGetUniformLocation(shaderProgram, "uLightDir");
        uLightColorLoc = GL20.glGetUniformLocation(shaderProgram, "uLightColor");
        uAmbientLoc = GL20.glGetUniformLocation(shaderProgram, "uAmbient");
        uCameraPosLoc = GL20.glGetUniformLocation(shaderProgram, "uCameraPos");
        uFogStartLoc = GL20.glGetUniformLocation(shaderProgram, "uFogStart");
        uFogEndLoc = GL20.glGetUniformLocation(shaderProgram, "uFogEnd");
        uFogColorLoc = GL20.glGetUniformLocation(shaderProgram, "uFogColor");
        uFogEnabledLoc = GL20.glGetUniformLocation(shaderProgram, "uFogEnabled");

        log.info("CharacterRenderer initialized with shader program: {}", shaderProgram);
    }

    /**
     * Uploads a CompositeCharacterModel to GPU memory as a unified VAO/VBO/EBO mesh.
     */
    public GpuCharacterMesh uploadModel(CompositeCharacterModel model) {
        if (model == null) {
            return null;
        }

        List<SkinnedVertex> allVertices = new ArrayList<>();
        List<Integer> allIndices = new ArrayList<>();

        for (SkinnedMesh mesh : model.equipmentSlots().values()) {
            for (SkinnedSubMesh sub : mesh.subMeshes()) {
                int baseVertex = allVertices.size();
                allVertices.addAll(sub.vertices());
                for (int idx : sub.indices()) {
                    allIndices.add(baseVertex + idx);
                }
            }
        }

        if (allVertices.isEmpty() || allIndices.isEmpty()) {
            return null;
        }

        int vaoId = GL30.glGenVertexArrays();
        int vboId = GL15.glGenBuffers();
        int eboId = GL15.glGenBuffers();

        // 64 bytes per vertex: pos(12) + normal(12) + uv(8) + color(16) + joints(8) + weights(8)
        ByteBuffer vBuffer = BufferUtils.createByteBuffer(allVertices.size() * 64);
        for (SkinnedVertex v : allVertices) {
            vBuffer.putFloat(v.position().x()).putFloat(v.position().y()).putFloat(v.position().z());
            vBuffer.putFloat(v.normal().x()).putFloat(v.normal().y()).putFloat(v.normal().z());
            vBuffer.putFloat(v.u()).putFloat(v.v());
            vBuffer.putFloat(v.r() / 255.0f).putFloat(v.g() / 255.0f).putFloat(v.b() / 255.0f).putFloat(v.a() / 255.0f);
            vBuffer.putInt(v.jointIndex0()).putInt(v.jointIndex1());
            vBuffer.putFloat(v.jointWeight0()).putFloat(v.jointWeight1());
        }
        vBuffer.flip();

        IntBuffer iBuffer = BufferUtils.createIntBuffer(allIndices.size());
        for (int idx : allIndices) {
            iBuffer.put(idx);
        }
        iBuffer.flip();

        GL30.glBindVertexArray(vaoId);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vBuffer, GL15.GL_STATIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, iBuffer, GL15.GL_STATIC_DRAW);

        int stride = 64;
        // 0: vec3 aPos (offset 0)
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);

        // 1: vec3 aNormal (offset 12)
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 12);
        GL20.glEnableVertexAttribArray(1);

        // 2: vec2 aTexCoord (offset 24)
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 24);
        GL20.glEnableVertexAttribArray(2);

        // 3: vec4 aColor (offset 32)
        GL20.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, stride, 32);
        GL20.glEnableVertexAttribArray(3);

        // 4: ivec2 aJoints (offset 48)
        GL30.glVertexAttribIPointer(4, 2, GL11.GL_INT, stride, 48);
        GL20.glEnableVertexAttribArray(4);

        // 5: vec2 aWeights (offset 56)
        GL20.glVertexAttribPointer(5, 2, GL11.GL_FLOAT, false, stride, 56);
        GL20.glEnableVertexAttribArray(5);

        GL30.glBindVertexArray(0);

        return new GpuCharacterMesh(vaoId, vboId, eboId, allIndices.size(), model);
    }

    /**
     * Renders a character model by job ID at the specified world coordinates and yaw heading.
     */
    public void render(short jobId, List<Matrix4f> poseMatrices,
                       float x, float y, float z, float yawDegrees,
                       float[] projectionMatrix, float[] viewMatrix,
                       DiurnalLighting lighting, float camX, float camY, float camZ) {
        if (shaderProgram == 0) {
            return;
        }

        GpuCharacterMesh mesh = jobModelCache.computeIfAbsent(jobId, id -> {
            CompositeCharacterModel model = CharacterMeshGenerator.createCharacterModel(id);
            return uploadModel(model);
        });

        if (mesh == null) {
            return;
        }

        renderMesh(mesh, poseMatrices, x, y, z, yawDegrees, projectionMatrix, viewMatrix, lighting, camX, camY, camZ);
    }

    /**
     * Renders an uploaded GPU character mesh given bone pose matrices, spatial coordinates, and lighting.
     */
    public void renderMesh(GpuCharacterMesh mesh, List<Matrix4f> poseMatrices,
                           float x, float y, float z, float yawDegrees,
                           float[] projectionMatrix, float[] viewMatrix,
                           DiurnalLighting lighting, float camX, float camY, float camZ) {
        if (shaderProgram == 0 || mesh == null || mesh.vaoId() == 0) {
            return;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);

        GL20.glUseProgram(shaderProgram);

        GL20.glUniformMatrix4fv(uProjectionLoc, false, projectionMatrix);
        GL20.glUniformMatrix4fv(uViewLoc, false, viewMatrix);

        float[] modelMat = MatrixMath.modelMatrix(x, y, z, yawDegrees);
        GL20.glUniformMatrix4fv(uModelLoc, false, modelMat);

        // Compute skinning matrices: S_i = Pose_i * InvBind_i
        List<Matrix4f> skinningMatrices = (poseMatrices != null && mesh.model() != null && mesh.model().skeleton() != null)
                ? mesh.model().skeleton().computeSkinningMatrices(poseMatrices)
                : List.of();

        bonesBuffer.clear();
        for (int i = 0; i < MAX_BONES; i++) {
            if (i < skinningMatrices.size()) {
                bonesBuffer.put(skinningMatrices.get(i).elements());
            } else {
                bonesBuffer.put(identityElements);
            }
        }
        bonesBuffer.flip();
        GL20.glUniformMatrix4fv(uBonesLoc, false, bonesBuffer);

        // Atmospheric lighting
        if (lighting != null) {
            GL20.glUniform3f(uLightDirLoc, lighting.getSunDirection().x(), lighting.getSunDirection().y(), lighting.getSunDirection().z());
            GL20.glUniform3f(uLightColorLoc, lighting.getSunColor()[0], lighting.getSunColor()[1], lighting.getSunColor()[2]);
            GL20.glUniform3f(uAmbientLoc, lighting.getAmbientColor()[0], lighting.getAmbientColor()[1], lighting.getAmbientColor()[2]);

            GL20.glUniform3f(uCameraPosLoc, camX, camY, camZ);
            GL20.glUniform1f(uFogStartLoc, lighting.getFogStart());
            GL20.glUniform1f(uFogEndLoc, lighting.getFogEnd());
            float[] fogColor = lighting.getFogColor();
            GL20.glUniform3f(uFogColorLoc, fogColor[0], fogColor[1], fogColor[2]);
            GL20.glUniform1i(uFogEnabledLoc, 1);
        } else {
            // Default daytime illumination
            GL20.glUniform3f(uLightDirLoc, 0.4f, 0.8f, 0.4f);
            GL20.glUniform3f(uLightColorLoc, 0.95f, 0.95f, 0.90f);
            GL20.glUniform3f(uAmbientLoc, 0.35f, 0.35f, 0.40f);
            GL20.glUniform1i(uFogEnabledLoc, 0);
        }

        GL30.glBindVertexArray(mesh.vaoId());
        GL11.glDrawElements(GL11.GL_TRIANGLES, mesh.indexCount(), GL11.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
    }

    public void cleanup() {
        for (GpuCharacterMesh mesh : jobModelCache.values()) {
            mesh.cleanup();
        }
        jobModelCache.clear();

        if (shaderProgram != 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
    }

    private int createShaderProgram(String vertexSrc, String fragmentSrc) {
        int vShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vShader, vertexSrc);
        GL20.glCompileShader(vShader);
        if (GL20.glGetShaderi(vShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String err = GL20.glGetShaderInfoLog(vShader);
            log.error("Character vertex shader compilation failed: {}", err);
            throw new RuntimeException("Character vertex shader compilation error: " + err);
        }

        int fShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fShader, fragmentSrc);
        GL20.glCompileShader(fShader);
        if (GL20.glGetShaderi(fShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String err = GL20.glGetShaderInfoLog(fShader);
            log.error("Character fragment shader compilation failed: {}", err);
            throw new RuntimeException("Character fragment shader compilation error: " + err);
        }

        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vShader);
        GL20.glAttachShader(prog, fShader);
        GL20.glLinkProgram(prog);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String err = GL20.glGetProgramInfoLog(prog);
            log.error("Character shader program linking failed: {}", err);
            throw new RuntimeException("Character shader program link error: " + err);
        }

        GL20.glDeleteShader(vShader);
        GL20.glDeleteShader(fShader);
        return prog;
    }
}
