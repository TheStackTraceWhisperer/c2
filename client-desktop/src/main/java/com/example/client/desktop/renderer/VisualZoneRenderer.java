package com.example.client.desktop.renderer;

import com.example.asset.mesh.VisualSubMesh;
import com.example.asset.mesh.VisualZoneMesh;
import com.example.asset.mesh.VisualZoneScene;
import com.example.asset.mesh.ZoneMeshVertex;
import com.example.asset.texture.DecodedTexture;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
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
 * High-performance textured 3D OpenGL renderer for FFXI visual zone meshes.
 * Batches submeshes by material/texture to minimize GPU state changes and draw calls.
 */
@Slf4j
public class VisualZoneRenderer {

    private static final String VERTEX_SHADER_SRC = """
            #version 460 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;
            layout (location = 3) in vec4 aColor;

            uniform mat4 uProjection;
            uniform mat4 uView;
            uniform mat4 uModel;

            out vec3 vNormal;
            out vec2 vTexCoord;
            out vec4 vColor;
            out vec3 vWorldPos;

            void main() {
                vec4 worldPos = uModel * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = mat3(transpose(inverse(uModel))) * aNormal;
                vTexCoord = aTexCoord;
                vColor = aColor;
                gl_Position = uProjection * uView * worldPos;
            }
            """;

    private static final String FRAGMENT_SHADER_SRC = """
            #version 460 core
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
            uniform sampler2D uTexture;
            uniform int uHasTexture;
            uniform float uAlphaThreshold;

            out vec4 FragColor;

            void main() {
                vec4 texColor = (uHasTexture == 1) ? texture(uTexture, vTexCoord) : vec4(0.8, 0.8, 0.8, 1.0);

                // Alpha discard test for foliage, fences, cutouts
                if (uAlphaThreshold > 0.0 && texColor.a < uAlphaThreshold) {
                    discard;
                }

                vec3 norm = normalize(vNormal);
                float diff = max(dot(norm, normalize(uLightDir)), 0.0);
                // Double-sided lighting fallback for thin flags / foliage
                diff = max(diff, max(dot(-norm, normalize(uLightDir)) * 0.4, 0.0));

                vec3 lighting = uAmbient + diff * uLightColor;
                vec3 finalRgb = 2.0 * texColor.rgb * vColor.rgb * lighting;

                if (uFogEnabled == 1) {
                    float dist = length(vWorldPos - uCameraPos);
                    float fogFactor = clamp((dist - uFogStart) / max(0.001, (uFogEnd - uFogStart)), 0.0, 1.0);
                    finalRgb = mix(finalRgb, uFogColor, fogFactor);
                }

                FragColor = vec4(finalRgb, clamp(4.0 * texColor.a * vColor.a, 0.0, 1.0));
            }
            """;

    private record BatchKey(String textureName, boolean blendEnabled, boolean backFaceCulling, boolean cutout) {}

    private static class SubMeshBatch {
        final BatchKey key;
        final List<ZoneMeshVertex> vertices = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        int vaoId = 0;
        int vboId = 0;
        int eboId = 0;
        int indexCount = 0;

        SubMeshBatch(BatchKey key) {
            this.key = key;
        }
    }

    private int shaderProgram = 0;
    private int uProjectionLoc = -1;
    private int uViewLoc = -1;
    private int uModelLoc = -1;
    private int uLightDirLoc = -1;
    private int uLightColorLoc = -1;
    private int uAmbientLoc = -1;
    private int uCameraPosLoc = -1;
    private int uFogStartLoc = -1;
    private int uFogEndLoc = -1;
    private int uFogColorLoc = -1;
    private int uFogEnabledLoc = -1;
    private int uTextureLoc = -1;
    private int uHasTextureLoc = -1;
    private int uAlphaThresholdLoc = -1;

    private final Map<String, Integer> glTextureMap = new HashMap<>();
    private final List<SubMeshBatch> opaqueBatches = new ArrayList<>();
    private final List<SubMeshBatch> blendedBatches = new ArrayList<>();

    private int totalVerticesUploaded = 0;
    private int totalTrianglesUploaded = 0;

    public void init() {
        shaderProgram = createShaderProgram(VERTEX_SHADER_SRC, FRAGMENT_SHADER_SRC);

        uProjectionLoc = GL20.glGetUniformLocation(shaderProgram, "uProjection");
        uViewLoc = GL20.glGetUniformLocation(shaderProgram, "uView");
        uModelLoc = GL20.glGetUniformLocation(shaderProgram, "uModel");
        uLightDirLoc = GL20.glGetUniformLocation(shaderProgram, "uLightDir");
        uLightColorLoc = GL20.glGetUniformLocation(shaderProgram, "uLightColor");
        uAmbientLoc = GL20.glGetUniformLocation(shaderProgram, "uAmbient");
        uCameraPosLoc = GL20.glGetUniformLocation(shaderProgram, "uCameraPos");
        uFogStartLoc = GL20.glGetUniformLocation(shaderProgram, "uFogStart");
        uFogEndLoc = GL20.glGetUniformLocation(shaderProgram, "uFogEnd");
        uFogColorLoc = GL20.glGetUniformLocation(shaderProgram, "uFogColor");
        uFogEnabledLoc = GL20.glGetUniformLocation(shaderProgram, "uFogEnabled");
        uTextureLoc = GL20.glGetUniformLocation(shaderProgram, "uTexture");
        uHasTextureLoc = GL20.glGetUniformLocation(shaderProgram, "uHasTexture");
        uAlphaThresholdLoc = GL20.glGetUniformLocation(shaderProgram, "uAlphaThreshold");

        log.info("VisualZoneRenderer initialized with shader program: {}", shaderProgram);
    }

    /**
     * Uploads all textures and geometry from a VisualZoneScene to OpenGL.
     */
    public void upload(VisualZoneScene scene) {
        cleanupBatches();

        if (scene == null) {
            return;
        }

        // 1. Upload textures to OpenGL
        for (Map.Entry<String, DecodedTexture> entry : scene.textures().entrySet()) {
            String name = entry.getKey();
            DecodedTexture tex = entry.getValue();
            int texId = uploadTextureToGl(tex);
            glTextureMap.put(name, texId);
        }
        log.info("Uploaded {} textures to OpenGL", glTextureMap.size());

        // 2. Group submeshes by BatchKey
        Map<BatchKey, SubMeshBatch> batchMap = new HashMap<>();

        if (scene.zoneObjects() != null && !scene.zoneObjects().isEmpty()) {
            // Authentic FFXI Zone Object instancing: place objects across the world
            for (com.example.asset.mesh.ZoneObject obj : scene.zoneObjects()) {
                com.example.asset.mesh.VisualZoneMesh mesh = scene.getMesh(obj.meshName()).orElse(null);
                if (mesh == null) {
                    continue;
                }
                float[] model = obj.toModelMatrix();
                boolean mirrored = obj.isMirrored();

                boolean cutout = obj.meshName().startsWith("_");
                for (VisualSubMesh subMesh : mesh.subMeshes()) {
                    String texName = subMesh.textureName() != null ? subMesh.textureName().trim().toLowerCase() : "";
                    BatchKey key = new BatchKey(texName, subMesh.blendEnabled(), subMesh.backFaceCulling(), cutout);
                    SubMeshBatch batch = batchMap.computeIfAbsent(key, SubMeshBatch::new);

                    int baseVertex = batch.vertices.size();
                    for (ZoneMeshVertex v : subMesh.vertices()) {
                        // Transform vertex position by object model matrix
                        float vx = model[0] * v.position().x() + model[4] * v.position().y() + model[8] * v.position().z() + model[12];
                        float vy = model[1] * v.position().x() + model[5] * v.position().y() + model[9] * v.position().z() + model[13];
                        float vz = model[2] * v.position().x() + model[6] * v.position().y() + model[10] * v.position().z() + model[14];

                        // Transform surface normal
                        float nx = model[0] * v.normal().x() + model[4] * v.normal().y() + model[8] * v.normal().z();
                        float ny = model[1] * v.normal().x() + model[5] * v.normal().y() + model[9] * v.normal().z();
                        float nz = model[2] * v.normal().x() + model[6] * v.normal().y() + model[10] * v.normal().z();
                        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (len > 1e-4f) {
                            nx /= len;
                            ny /= len;
                            nz /= len;
                        }

                        batch.vertices.add(new ZoneMeshVertex(
                                new com.example.asset.geometry.Vector3f(vx, vy, vz),
                                new com.example.asset.geometry.Vector3f(nx, ny, nz),
                                v.u(), v.v(), v.r(), v.g(), v.b(), v.a()
                        ));
                    }

                    List<Integer> subIndices = subMesh.indices();
                    if (mirrored) {
                        // Flip winding for mirrored objects to preserve CW backface culling
                        for (int i = 0; i + 2 < subIndices.size(); i += 3) {
                            batch.indices.add(baseVertex + subIndices.get(i + 2));
                            batch.indices.add(baseVertex + subIndices.get(i + 1));
                            batch.indices.add(baseVertex + subIndices.get(i));
                        }
                    } else {
                        for (int idx : subIndices) {
                            batch.indices.add(baseVertex + idx);
                        }
                    }
                }
            }
        } else {
            // Standalone meshes fallback (no zone definition)
            for (VisualZoneMesh mesh : scene.meshes()) {
                boolean cutout = mesh.name().startsWith("_");
                for (VisualSubMesh subMesh : mesh.subMeshes()) {
                    String texName = subMesh.textureName() != null ? subMesh.textureName().trim().toLowerCase() : "";
                    BatchKey key = new BatchKey(texName, subMesh.blendEnabled(), subMesh.backFaceCulling(), cutout);
                    SubMeshBatch batch = batchMap.computeIfAbsent(key, SubMeshBatch::new);

                    int baseVertex = batch.vertices.size();
                    batch.vertices.addAll(subMesh.vertices());
                    for (int idx : subMesh.indices()) {
                        batch.indices.add(baseVertex + idx);
                    }
                }
            }
        }

        // 3. Upload batches to GPU VAO/VBO/EBO
        totalVerticesUploaded = 0;
        totalTrianglesUploaded = 0;

        for (SubMeshBatch batch : batchMap.values()) {
            if (batch.indices.isEmpty()) {
                continue;
            }
            uploadBatchToGpu(batch);
            if (batch.key.blendEnabled()) {
                blendedBatches.add(batch);
            } else {
                opaqueBatches.add(batch);
            }
            totalVerticesUploaded += batch.vertices.size();
            totalTrianglesUploaded += batch.indexCount / 3;
        }

        log.info("Uploaded {} opaque batches, {} blended batches (total {} vertices, {} triangles)",
                opaqueBatches.size(), blendedBatches.size(), totalVerticesUploaded, totalTrianglesUploaded);
    }

    private void uploadBatchToGpu(SubMeshBatch batch) {
        batch.indexCount = batch.indices.size();

        // 12 floats per vertex: pos(3), normal(3), uv(2), color(4)
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(batch.vertices.size() * 12);
        for (ZoneMeshVertex v : batch.vertices) {
            vertexBuffer.put(v.position().x()).put(v.position().y()).put(v.position().z());
            vertexBuffer.put(v.normal().x()).put(v.normal().y()).put(v.normal().z());
            vertexBuffer.put(v.u()).put(v.v());
            vertexBuffer.put(v.r() / 255.0f).put(v.g() / 255.0f).put(v.b() / 255.0f).put(v.a() / 255.0f);
        }
        vertexBuffer.flip();

        IntBuffer indexBuffer = BufferUtils.createIntBuffer(batch.indices.size());
        for (int idx : batch.indices) {
            indexBuffer.put(idx);
        }
        indexBuffer.flip();

        batch.vaoId = GL30.glGenVertexArrays();
        batch.vboId = GL15.glGenBuffers();
        batch.eboId = GL15.glGenBuffers();

        GL30.glBindVertexArray(batch.vaoId);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batch.vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, batch.eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);

        int stride = 12 * Float.BYTES;
        // Position
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // Normal
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        // UV
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        // Color
        GL20.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, stride, 8 * Float.BYTES);
        GL20.glEnableVertexAttribArray(3);

        GL30.glBindVertexArray(0);
    }

    private int uploadTextureToGl(DecodedTexture tex) {
        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);

        ByteBuffer buf = BufferUtils.createByteBuffer(tex.rgbaPixels().length);
        buf.put(tex.rgbaPixels());
        buf.flip();

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, tex.width(), tex.height(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texId;
    }

    /**
     * Renders all visual zone batches with default daytime lighting.
     */
    public void render(float[] projectionMatrix, float[] viewMatrix, boolean wireframe) {
        render(projectionMatrix, viewMatrix, wireframe, null, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Renders all visual zone batches with dynamic diurnal celestial lighting and distance fog.
     */
    public void render(float[] projectionMatrix, float[] viewMatrix, boolean wireframe,
                       com.example.asset.environment.DiurnalLighting lighting,
                       float camX, float camY, float camZ) {
        if (opaqueBatches.isEmpty() && blendedBatches.isEmpty()) {
            return;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        GL20.glUseProgram(shaderProgram);

        GL20.glUniformMatrix4fv(uProjectionLoc, false, projectionMatrix);
        GL20.glUniformMatrix4fv(uViewLoc, false, viewMatrix);

        float[] identity = new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
        GL20.glUniformMatrix4fv(uModelLoc, false, identity);

        if (lighting != null) {
            GL20.glUniform3f(uLightDirLoc, lighting.getSunDirection().x(), lighting.getSunDirection().y(), lighting.getSunDirection().z());
            GL20.glUniform3f(uLightColorLoc, lighting.getSunColor()[0], lighting.getSunColor()[1], lighting.getSunColor()[2]);
            GL20.glUniform3f(uAmbientLoc, lighting.getAmbientColor()[0], lighting.getAmbientColor()[1], lighting.getAmbientColor()[2]);
            GL20.glUniform3f(uCameraPosLoc, camX, camY, camZ);
            GL20.glUniform1f(uFogStartLoc, lighting.getFogStart());
            GL20.glUniform1f(uFogEndLoc, lighting.getFogEnd());
            GL20.glUniform3f(uFogColorLoc, lighting.getFogColor()[0], lighting.getFogColor()[1], lighting.getFogColor()[2]);
            GL20.glUniform1i(uFogEnabledLoc, 1);
        } else {
            // Default midday lighting
            GL20.glUniform3f(uLightDirLoc, 0.4f, 1.0f, 0.5f);
            GL20.glUniform3f(uLightColorLoc, 1.0f, 0.96f, 0.90f);
            GL20.glUniform3f(uAmbientLoc, 0.40f, 0.40f, 0.44f);
            GL20.glUniform1i(uFogEnabledLoc, 0);
        }

        GL20.glUniform1f(uAlphaThresholdLoc, 0.375f);
        GL20.glUniform1i(uTextureLoc, 0);

        if (wireframe) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        } else {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }

        // Pass 1: Opaque batches (depth write ON, blending OFF)
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(true);
        for (SubMeshBatch batch : opaqueBatches) {
            renderBatch(batch);
        }

        // Pass 2: Blended batches (depth write OFF or LEQUAL, blending ON)
        if (!blendedBatches.isEmpty()) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            for (SubMeshBatch batch : blendedBatches) {
                renderBatch(batch);
            }
            GL11.glDisable(GL11.GL_BLEND);
        }

        if (wireframe) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }

        GL20.glUseProgram(0);
    }

    private void renderBatch(SubMeshBatch batch) {
        float threshold = batch.key.cutout() ? 0.375f : 0.0f;
        GL20.glUniform1f(uAlphaThresholdLoc, threshold);

        if (batch.key.backFaceCulling()) {
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glFrontFace(GL11.GL_CCW);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }

        Integer texId = glTextureMap.get(batch.key.textureName());
        if (texId == null && batch.key.textureName() != null) {
            String name = batch.key.textureName().trim().toLowerCase();
            texId = glTextureMap.get(name);
            if (texId == null && name.contains(" ")) {
                String[] parts = name.split("\\s+");
                texId = glTextureMap.get(parts[parts.length - 1]);
            }
        }
        if (texId != null && texId > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL20.glUniform1i(uHasTextureLoc, 1);
        } else {
            GL20.glUniform1i(uHasTextureLoc, 0);
        }

        GL30.glBindVertexArray(batch.vaoId);
        GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount, GL11.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
    }

    public int totalVerticesUploaded() {
        return totalVerticesUploaded;
    }

    public int totalTrianglesUploaded() {
        return totalTrianglesUploaded;
    }

    private static int createShaderProgram(String vertexSrc, String fragmentSrc) {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSrc);
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSrc);

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String infoLog = GL20.glGetProgramInfoLog(program);
            throw new IllegalStateException("Failed to link visual zone shader: " + infoLog);
        }

        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String infoLog = GL20.glGetShaderInfoLog(shader);
            throw new IllegalStateException("Failed to compile shader (" + type + "): " + infoLog);
        }
        return shader;
    }

    private void cleanupBatches() {
        for (SubMeshBatch batch : opaqueBatches) {
            deleteBatch(batch);
        }
        opaqueBatches.clear();

        for (SubMeshBatch batch : blendedBatches) {
            deleteBatch(batch);
        }
        blendedBatches.clear();

        for (int texId : glTextureMap.values()) {
            if (texId > 0) {
                GL11.glDeleteTextures(texId);
            }
        }
        glTextureMap.clear();
    }

    private void deleteBatch(SubMeshBatch batch) {
        if (batch.vaoId != 0) {
            GL30.glDeleteVertexArrays(batch.vaoId);
            batch.vaoId = 0;
        }
        if (batch.vboId != 0) {
            GL15.glDeleteBuffers(batch.vboId);
            batch.vboId = 0;
        }
        if (batch.eboId != 0) {
            GL15.glDeleteBuffers(batch.eboId);
            batch.eboId = 0;
        }
    }

    public void cleanup() {
        cleanupBatches();
        if (shaderProgram != 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
    }
}
