package com.example.client.desktop.renderer;

import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.TerrainType;
import com.example.asset.geometry.Vector3f;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * High-performance 3D OpenGL renderer for zone collision meshes and world geometry.
 */
@Slf4j
public class CollisionMeshRenderer {

    private static final String VERTEX_SHADER_SRC = """
            #version 460 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec3 aColor;

            uniform mat4 uProjection;
            uniform mat4 uView;
            uniform mat4 uModel;

            out vec3 vNormal;
            out vec3 vColor;
            out vec3 vWorldPos;

            void main() {
                vec4 worldPos = uModel * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = mat3(transpose(inverse(uModel))) * aNormal;
                vColor = aColor;
                gl_Position = uProjection * uView * worldPos;
            }
            """;

    private static final String FRAGMENT_SHADER_SRC = """
            #version 460 core
            in vec3 vNormal;
            in vec3 vColor;
            in vec3 vWorldPos;

            uniform vec3 uLightDir;
            uniform vec3 uLightColor;
            uniform vec3 uAmbient;
            uniform int uWireframe;

            out vec4 FragColor;

            void main() {
                if (uWireframe == 1) {
                    FragColor = vec4(vColor * 1.5, 1.0);
                    return;
                }
                vec3 norm = normalize(vNormal);
                // Double-sided lighting for collision geometry
                float diff = max(abs(dot(norm, normalize(uLightDir))), 0.0);
                vec3 diffuse = diff * uLightColor;
                vec3 lighting = uAmbient + diffuse;
                FragColor = vec4(vColor * lighting, 1.0);
            }
            """;

    private int shaderProgram = 0;
    private int uProjectionLoc = -1;
    private int uViewLoc = -1;
    private int uModelLoc = -1;
    private int uLightDirLoc = -1;
    private int uLightColorLoc = -1;
    private int uAmbientLoc = -1;
    private int uWireframeLoc = -1;

    private int vaoId = 0;
    private int vboId = 0;
    private int vertexCount = 0;

    // Player marker geometry
    private int playerVaoId = 0;
    private int playerVboId = 0;
    private int playerVertexCount = 0;

    // Town NPC marker batch geometry
    private int npcVaoId = 0;
    private int npcVboId = 0;
    private int npcVertexCount = 0;

    public void init() {
        shaderProgram = createShaderProgram(VERTEX_SHADER_SRC, FRAGMENT_SHADER_SRC);

        uProjectionLoc = GL20.glGetUniformLocation(shaderProgram, "uProjection");
        uViewLoc = GL20.glGetUniformLocation(shaderProgram, "uView");
        uModelLoc = GL20.glGetUniformLocation(shaderProgram, "uModel");
        uLightDirLoc = GL20.glGetUniformLocation(shaderProgram, "uLightDir");
        uLightColorLoc = GL20.glGetUniformLocation(shaderProgram, "uLightColor");
        uAmbientLoc = GL20.glGetUniformLocation(shaderProgram, "uAmbient");
        uWireframeLoc = GL20.glGetUniformLocation(shaderProgram, "uWireframe");

        initPlayerMarker();
        log.info("CollisionMeshRenderer initialized with shader program id: {}", shaderProgram);
    }

    public void upload(List<CollisionTriangle> triangles) {
        if (triangles == null || triangles.isEmpty()) {
            return;
        }

        this.vertexCount = triangles.size() * 3;
        FloatBuffer buffer = BufferUtils.createFloatBuffer(this.vertexCount * 9); // pos(3) + normal(3) + color(3)

        for (CollisionTriangle tri : triangles) {
            float[] color = getTerrainColor(tri);
            writeVertex(buffer, tri.v0(), tri.normal(), color);
            writeVertex(buffer, tri.v1(), tri.normal(), color);
            writeVertex(buffer, tri.v2(), tri.normal(), color);
        }
        buffer.flip();

        if (vaoId == 0) {
            vaoId = GL30.glGenVertexArrays();
        }
        if (vboId == 0) {
            vboId = GL15.glGenBuffers();
        }

        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

        int stride = 9 * Float.BYTES;
        // Position
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // Normal
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        // Color
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);

        GL30.glBindVertexArray(0);
        log.info("Uploaded {} collision triangles ({} vertices) to GPU", triangles.size(), vertexCount);
    }

    public void render(float[] projectionMatrix, float[] viewMatrix, boolean wireframe) {
        if (vaoId == 0 || vertexCount == 0) {
            return;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        GL20.glUseProgram(shaderProgram);

        // Upload uniforms
        GL20.glUniformMatrix4fv(uProjectionLoc, false, projectionMatrix);
        GL20.glUniformMatrix4fv(uViewLoc, false, viewMatrix);

        float[] identity = new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
        GL20.glUniformMatrix4fv(uModelLoc, false, identity);

        // Sun light
        GL20.glUniform3f(uLightDirLoc, 0.4f, 1.0f, 0.5f);
        GL20.glUniform3f(uLightColorLoc, 1.0f, 0.98f, 0.92f);
        GL20.glUniform3f(uAmbientLoc, 0.35f, 0.35f, 0.38f);
        GL20.glUniform1i(uWireframeLoc, wireframe ? 1 : 0);

        if (wireframe) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        } else {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }

        GL30.glBindVertexArray(vaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        GL30.glBindVertexArray(0);

        if (wireframe) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }

        GL20.glUseProgram(0);
    }

    public void renderPlayerMarker(float x, float y, float z, float yawDegrees, float[] projectionMatrix, float[] viewMatrix) {
        if (playerVaoId == 0) {
            return;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(shaderProgram);

        GL20.glUniformMatrix4fv(uProjectionLoc, false, projectionMatrix);
        GL20.glUniformMatrix4fv(uViewLoc, false, viewMatrix);

        // Translation to player pos
        float[] model = new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                x, y, z, 1f
        };
        GL20.glUniformMatrix4fv(uModelLoc, false, model);
        GL20.glUniform1i(uWireframeLoc, 0);
        GL20.glUniform3f(uAmbientLoc, 0.8f, 0.8f, 0.8f);

        GL30.glBindVertexArray(playerVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, playerVertexCount);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
    }

    public void renderRemoteMarker(float x, float y, float z, float yawDegrees, float r, float g, float b, float[] projectionMatrix, float[] viewMatrix) {
        if (playerVaoId == 0) {
            return;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(shaderProgram);

        GL20.glUniformMatrix4fv(uProjectionLoc, false, projectionMatrix);
        GL20.glUniformMatrix4fv(uViewLoc, false, viewMatrix);

        float[] model = new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                x, y, z, 1f
        };
        GL20.glUniformMatrix4fv(uModelLoc, false, model);
        GL20.glUniform1i(uWireframeLoc, 0);
        GL20.glUniform3f(uAmbientLoc, r, g, b);

        GL30.glBindVertexArray(playerVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, playerVertexCount);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
    }

    public void uploadNpcMarkers(List<com.example.asset.npc.NpcInfo> npcs) {
        if (npcs == null || npcs.isEmpty()) {
            return;
        }

        float r = 0.35f;
        float yBottom = 0.1f;
        float yMid = 0.9f;
        float yTop = 1.6f;
        float[] color = new float[]{0.95f, 0.78f, 0.22f}; // Golden Amber for Town NPCs

        int segments = 6;
        int trisPerNpc = segments * 2;
        int vertsPerNpc = trisPerNpc * 3;
        this.npcVertexCount = npcs.size() * vertsPerNpc;

        FloatBuffer buf = BufferUtils.createFloatBuffer(npcVertexCount * 9);

        for (com.example.asset.npc.NpcInfo npc : npcs) {
            float nx = npc.position().x();
            float ny = npc.position().y();
            float nz = npc.position().z();

            for (int i = 0; i < segments; i++) {
                double a0 = 2 * Math.PI * i / segments;
                double a1 = 2 * Math.PI * (i + 1) / segments;

                float x0 = nx + (float) Math.cos(a0) * r;
                float z0 = nz + (float) Math.sin(a0) * r;
                float x1 = nx + (float) Math.cos(a1) * r;
                float z1 = nz + (float) Math.sin(a1) * r;

                Vector3f top = new Vector3f(nx, ny + yTop, nz);
                Vector3f v0 = new Vector3f(x0, ny + yMid, z0);
                Vector3f v1 = new Vector3f(x1, ny + yMid, z1);
                Vector3f nTop = v0.sub(top).cross(v1.sub(top)).normalize();
                writeVertex(buf, top, nTop, color);
                writeVertex(buf, v0, nTop, color);
                writeVertex(buf, v1, nTop, color);

                Vector3f bottom = new Vector3f(nx, ny + yBottom, nz);
                Vector3f nBot = v1.sub(bottom).cross(v0.sub(bottom)).normalize();
                writeVertex(buf, bottom, nBot, color);
                writeVertex(buf, v1, nBot, color);
                writeVertex(buf, v0, nBot, color);
            }
        }
        buf.flip();

        if (npcVaoId == 0) {
            npcVaoId = GL30.glGenVertexArrays();
        }
        if (npcVboId == 0) {
            npcVboId = GL15.glGenBuffers();
        }

        GL30.glBindVertexArray(npcVaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, npcVboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);

        int stride = 9 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);

        GL30.glBindVertexArray(0);
        log.info("Uploaded {} town NPC markers ({} vertices) to GPU", npcs.size(), npcVertexCount);
    }

    public void renderNpcMarkers(float[] projectionMatrix, float[] viewMatrix) {
        if (npcVaoId == 0 || npcVertexCount == 0) {
            return;
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
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
        GL20.glUniform1i(uWireframeLoc, 0);
        GL20.glUniform3f(uAmbientLoc, 0.85f, 0.85f, 0.85f);

        GL30.glBindVertexArray(npcVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, npcVertexCount);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
    }

    private void initPlayerMarker() {
        // Build a diamond marker: apex at y=1.8, base at y=0.2, center at y=1.0, radius=0.4
        float r = 0.4f;
        float yBottom = 0.1f;
        float yMid = 0.9f;
        float yTop = 1.7f;
        float[] color = new float[]{0.1f, 0.9f, 0.9f}; // Cyan

        int segments = 8;
        playerVertexCount = segments * 6; // 8 top triangles + 8 bottom triangles
        FloatBuffer buf = BufferUtils.createFloatBuffer(playerVertexCount * 9);

        for (int i = 0; i < segments; i++) {
            double a0 = 2 * Math.PI * i / segments;
            double a1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) Math.cos(a0) * r;
            float z0 = (float) Math.sin(a0) * r;
            float x1 = (float) Math.cos(a1) * r;
            float z1 = (float) Math.sin(a1) * r;

            // Top pyramid triangle (apex, v0, v1)
            Vector3f top = new Vector3f(0f, yTop, 0f);
            Vector3f v0 = new Vector3f(x0, yMid, z0);
            Vector3f v1 = new Vector3f(x1, yMid, z1);
            Vector3f nTop = v0.sub(top).cross(v1.sub(top)).normalize();
            writeVertex(buf, top, nTop, color);
            writeVertex(buf, v0, nTop, color);
            writeVertex(buf, v1, nTop, color);

            // Bottom pyramid triangle (bottom, v1, v0)
            Vector3f bottom = new Vector3f(0f, yBottom, 0f);
            Vector3f nBot = v1.sub(bottom).cross(v0.sub(bottom)).normalize();
            writeVertex(buf, bottom, nBot, color);
            writeVertex(buf, v1, nBot, color);
            writeVertex(buf, v0, nBot, color);
        }
        buf.flip();

        playerVaoId = GL30.glGenVertexArrays();
        playerVboId = GL15.glGenBuffers();

        GL30.glBindVertexArray(playerVaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, playerVboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);

        int stride = 9 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);

        GL30.glBindVertexArray(0);
    }

    private static void writeVertex(FloatBuffer buffer, Vector3f pos, Vector3f normal, float[] color) {
        buffer.put(pos.x()).put(pos.y()).put(pos.z());
        buffer.put(normal.x()).put(normal.y()).put(normal.z());
        buffer.put(color[0]).put(color[1]).put(color[2]);
    }

    private static float[] getTerrainColor(CollisionTriangle tri) {
        if (tri.flags().isHitWall()) {
            return new float[]{0.70f, 0.22f, 0.22f}; // Crimson barrier
        }
        return switch (tri.terrainType()) {
            case GRASS -> new float[]{0.22f, 0.58f, 0.24f};
            case PATH -> new float[]{0.55f, 0.52f, 0.42f};
            case STONE -> new float[]{0.48f, 0.48f, 0.50f};
            case WOOD -> new float[]{0.54f, 0.38f, 0.22f};
            case METAL -> new float[]{0.38f, 0.42f, 0.48f};
            case SAND -> new float[]{0.72f, 0.68f, 0.38f};
            case SNOW -> new float[]{0.85f, 0.88f, 0.92f};
            case SHALLOW_WATER -> new float[]{0.20f, 0.50f, 0.78f};
            case DEEP_WATER -> new float[]{0.10f, 0.25f, 0.60f};
            default -> new float[]{0.42f, 0.42f, 0.45f};
        };
    }

    private static int createShaderProgram(String vertexSrc, String fragmentSrc) {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSrc);
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSrc);

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            throw new IllegalStateException("Failed to link shader program: " + log);
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
            String log = GL20.glGetShaderInfoLog(shader);
            throw new IllegalStateException("Failed to compile shader (" + type + "): " + log);
        }
        return shader;
    }

    public void cleanup() {
        if (vaoId != 0) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (vboId != 0) {
            GL15.glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (playerVaoId != 0) {
            GL30.glDeleteVertexArrays(playerVaoId);
            playerVaoId = 0;
        }
        if (playerVboId != 0) {
            GL15.glDeleteBuffers(playerVboId);
            playerVboId = 0;
        }
        if (npcVaoId != 0) {
            GL30.glDeleteVertexArrays(npcVaoId);
            npcVaoId = 0;
        }
        if (npcVboId != 0) {
            GL15.glDeleteBuffers(npcVboId);
            npcVboId = 0;
        }
        if (shaderProgram != 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
    }
}
