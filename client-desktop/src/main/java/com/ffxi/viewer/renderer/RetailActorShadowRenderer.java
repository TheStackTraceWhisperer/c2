package com.ffxi.viewer.renderer;

import com.example.asset.collision.CollisionMap;
import com.example.asset.collision.CollisionObject;
import com.example.asset.collision.CollisionObjectGroup;
import com.example.asset.collision.CollisionTransform;
import com.example.asset.config.FfxiPathConfig;
import com.example.asset.geometry.CollisionTriangle;
import com.ffxi.export.TextureDecoder;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.TextureResource;
import com.ffxi.parser.DatParser;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.environment.ZoneEnvironmentTable;
import com.ffxi.viewer.environment.ZonePointLight;
import com.ffxi.viewer.shader.GlShaderUtil;
import lombok.extern.slf4j.Slf4j;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.glTexImage3D;
import static org.lwjgl.opengl.GL13C.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL14C.glBlendEquation;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;

/**
 * Authentic retail FFXI per-actor ground shadow renderer matching {@code references/vana-client}
 * ({@code render/shadow.rs} and {@code client/shadows.rs}):
 * <ul>
 *     <li><b>HIGH</b>: renders skinned actor flat-white silhouettes from the strongest in-range floor
 *     point light (else directional sun/moon) into an {@code R8} silhouette texture array and projects
 *     onto nearby 3x3 collision triangles.</li>
 *     <li><b>NORMAL / fallback</b>: projects retail {@code system  kage_tex} ({@code 0x1b8c} / {@code ROM/27/81.DAT})
 *     top-down onto local 3x3 collision triangles or a flat feet quad beyond {@value #QUAD_DISTANCE_THRESHOLD}m.</li>
 * </ul>
 * Multiplies the destination framebuffer by {@code dst.rgb * (1 - src.rgb)} with Z-bias 15 and no fog.
 */
@Slf4j
public final class RetailActorShadowRenderer implements AutoCloseable {

    public enum ShadowMode {
        OFF,
        NORMAL,
        HIGH
    }

    public static final int KAGE_FILE_ID = 0x1b8c;
    public static final int SILHOUETTE_RES = 256;
    public static final int MAX_SILHOUETTES = 16;
    public static final float QUAD_DISTANCE_THRESHOLD = 20.0f;

    private static final float SILHOUETTE_FOV_RAD = 0.19634954f;

    private static final String VERTEX_SHADER = """
            #version 460 core

            layout(location=0) in vec3 aPos;

            uniform mat4 uViewProj;
            uniform int uProjMode; // 0 = projected kage blob, 1 = silhouette ground, 2 = flat quad
            uniform vec3 uActorPos;
            uniform vec3 uLightDir;
            uniform float uSizeParam; // blob diameter sb (mode 0/2) or silhouette extent sizeS (mode 1)
            uniform int uLayer;

            out vec2 vUv;
            out float vFalloff;
            flat out int vLayer;

            void main() {
                vec4 clip = uViewProj * vec4(aPos, 1.0);
                clip.z -= 15.0 * 5e-6 * clip.w;
                gl_Position = clip;

                vec3 d = aPos - uActorPos;
                if (uProjMode == 1) {
                    float hl = max(length(uLightDir.xz), 1e-6);
                    float u = dot(d, vec3(uLightDir.z, 0.0, -uLightDir.x) / hl) / max(uSizeParam, 1e-4) + 0.5;
                    // OpenGL FBO origin sits at bottom-left (+clip.y -> v=1), opposite to D3D/wgpu top-left.
                    float v = 0.5 - dot(d, vec3(uLightDir.x, 0.0, uLightDir.z) / hl) / max(uSizeParam, 1e-4);
                    vUv = vec2(u, v);
                    vFalloff = max(0.0, 64.0 - trunc(32.0 * abs(d.y))) / 255.0;
                } else if (uProjMode == 2) {
                    vUv = d.xz / max(uSizeParam, 1e-4) + 0.5;
                    vFalloff = 96.0 / 255.0;
                } else {
                    vUv = d.xz / max(uSizeParam, 1e-4) + 0.5;
                    vFalloff = max(0.0, 96.0 - trunc(48.0 * abs(d.y))) / 255.0;
                }
                vLayer = uLayer;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 460 core

            uniform sampler2D uKageTex;
            uniform sampler2DArray uSilArray;
            uniform int uProjMode;

            in vec2 vUv;
            in float vFalloff;
            flat in int vLayer;

            out vec4 outColor;

            void main() {
                if (vUv.x <= 0.0 || vUv.x >= 1.0 || vUv.y <= 0.0 || vUv.y >= 1.0 || vFalloff <= 0.0) {
                    discard;
                }
                if (uProjMode == 1) {
                    float t = texture(uSilArray, vec3(vUv, float(vLayer))).r;
                    float s = clamp(t * vFalloff * 2.0, 0.0, 1.0);
                    outColor = vec4(s, s, s, 1.0);
                } else {
                    vec3 t = texture(uKageTex, vUv).rgb;
                    outColor = vec4(clamp(t * vFalloff * 2.0, 0.0, 1.0), 1.0);
                }
            }
            """;

    private final int programId;
    private final int uViewProjLoc;
    private final int uProjModeLoc;
    private final int uActorPosLoc;
    private final int uLightDirLoc;
    private final int uSizeParamLoc;
    private final int uLayerLoc;

    private final int kageTexId;
    private final boolean retailKageTextureLoaded;
    private final int silFboId;
    private final int silArrayTexId;

    private final int triVaoId;
    private final int triVboId;
    private int triVboCapacityFloats = 0;
    private FloatBuffer triScratch = BufferUtils.createFloatBuffer(4096 * 3);

    private final int quadVaoId;
    private final int quadVboId;
    private final FloatBuffer quadScratch = BufferUtils.createFloatBuffer(6 * 3);

    public RetailActorShadowRenderer(Path ffxiRoot) {
        this(ffxiRoot, null);
    }

    public RetailActorShadowRenderer(Path ffxiRoot, FileTableManager fileTableManager) {
        this.programId = GlShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER, "RetailActorShadowRenderer");
        this.uViewProjLoc = glGetUniformLocation(programId, "uViewProj");
        this.uProjModeLoc = glGetUniformLocation(programId, "uProjMode");
        this.uActorPosLoc = glGetUniformLocation(programId, "uActorPos");
        this.uLightDirLoc = glGetUniformLocation(programId, "uLightDir");
        this.uSizeParamLoc = glGetUniformLocation(programId, "uSizeParam");
        this.uLayerLoc = glGetUniformLocation(programId, "uLayer");

        glUseProgram(programId);
        glUniform1i(glGetUniformLocation(programId, "uKageTex"), 0);
        glUniform1i(glGetUniformLocation(programId, "uSilArray"), 1);
        glUseProgram(0);

        int[] kageResult = createKageTexture(ffxiRoot, fileTableManager);
        this.kageTexId = kageResult[0];
        this.retailKageTextureLoaded = kageResult[1] != 0;

        this.silArrayTexId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, silArrayTexId);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_R8, SILHOUETTE_RES, SILHOUETTE_RES, MAX_SILHOUETTES,
                0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_BORDER_COLOR, new float[]{0f, 0f, 0f, 0f});
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        this.silFboId = glGenFramebuffers();

        this.triVaoId = glGenVertexArrays();
        this.triVboId = glGenBuffers();
        glBindVertexArray(triVaoId);
        glBindBuffer(GL_ARRAY_BUFFER, triVboId);
        glBufferData(GL_ARRAY_BUFFER, 4096L * 3 * Float.BYTES, GL_DYNAMIC_DRAW);
        this.triVboCapacityFloats = 4096 * 3;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);

        this.quadVaoId = glGenVertexArrays();
        this.quadVboId = glGenBuffers();
        glBindVertexArray(quadVaoId);
        glBindBuffer(GL_ARRAY_BUFFER, quadVboId);
        glBufferData(GL_ARRAY_BUFFER, 6L * 3 * Float.BYTES, GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public boolean isRetailKageTextureLoaded() {
        return retailKageTextureLoaded;
    }

    public interface ActorShadowSource {
        boolean isVisible();
        boolean isLocalPlayer();
        AuthenticCharacterInstance getVisualModel();
        float getX();
        float getY();
        float getZ();
        float getYaw();
    }

    private record PreparedShadow(
            ActorShadowSource actor,
            int projMode, // 0 blob, 1 silhouette, 2 quad
            int layer,
            Vector3f actorPos,
            Vector3f lightDir,
            float sizeParam,
            Matrix4f lightProj,
            Matrix4f lightView,
            float cameraDistanceSq,
            List<CollisionTriangle> triangles
    ) {
    }

    /**
     * Resolves shadow directions, renders skinned silhouettes into the {@code R8} texture array, and
     * darkens the ground beneath all visible actors using retail {@code GL_ZERO, GL_ONE_MINUS_SRC_COLOR} blending.
     */
    public void renderShadows(
            int targetFbo,
            int viewportWidth,
            int viewportHeight,
            Matrix4f projMatrix,
            Matrix4f viewMatrix,
            Vector3f cameraPos,
            Collection<? extends ActorShadowSource> actors,
            CharacterShader characterShader,
            CollisionMap collisionMap,
            List<ZonePointLight> pointLights,
            float[] pointLightIntensities,
            ZoneEnvironmentTable.ResolvedEnvironment zoneEnv,
            ShadowMode mode
    ) {
        if (mode == ShadowMode.OFF || actors == null || actors.isEmpty() || collisionMap == null || zoneEnv == null) {
            return;
        }

        List<ActorShadowSource> candidates = new ArrayList<>();
        for (ActorShadowSource actor : actors) {
            if (actor != null && actor.isVisible() && actor.getVisualModel() != null) {
                candidates.add(actor);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        // Local player first, then sorted nearest-to-camera so silhouette layers go to the closest actors
        candidates.sort(Comparator
                .comparing((ActorShadowSource a) -> !a.isLocalPlayer())
                .thenComparingDouble(a -> distSq(cameraPos, a.getX(), a.getY(), a.getZ())));

        List<PreparedShadow> prepared = new ArrayList<>(candidates.size());
        int silLayer = 0;
        Vector3f fallbackLight = new Vector3f(zoneEnv.actorLightDir());

        for (ActorShadowSource actor : candidates) {
            float ax = actor.getX();
            float ay = actor.getY();
            float az = actor.getZ();
            float camDistSq = distSq(cameraPos, ax, ay, az);
            float camDist = (float) Math.sqrt(camDistSq);

            CollisionTransform floorTx = collisionMap
                    .findFloorTransform(new com.example.asset.geometry.Vector3f(ax, ay, az), 0.5f, 4.0f)
                    .orElse(null);

            Vector3f lightDir = resolveShadowLightDirection(
                    ax, ay, az, floorTx, pointLights, pointLightIntensities, fallbackLight
            );

            AuthenticCharacterInstance.WorldBounds bounds =
                    actor.getVisualModel().computePosedWorldBounds(ax, ay, az, actor.getYaw());
            float projectedSize = computeProjectedShadowSize(ax, ay, az, lightDir, bounds);

            boolean wantSilhouette = (mode == ShadowMode.HIGH)
                    && (silLayer < MAX_SILHOUETTES)
                    && (characterShader != null)
                    && (camDist < QUAD_DISTANCE_THRESHOLD);

            List<CollisionTriangle> tris = (camDist < QUAD_DISTANCE_THRESHOLD)
                    ? collectNearbyShadowTriangles(collisionMap, ax, ay, az, projectedSize, wantSilhouette)
                    : List.of();

            int projMode;
            float sizeParam;
            int assignedLayer = 0;
            Matrix4f lightProj = null;
            Matrix4f lightView = null;

            if (tris.isEmpty() || camDist >= QUAD_DISTANCE_THRESHOLD) {
                projMode = 2;
                sizeParam = (projectedSize * (5.0f / 9.0f)) / 3.0f;
            } else if (wantSilhouette) {
                projMode = 1;
                assignedLayer = silLayer++;
                sizeParam = projectedSize * 2.4f;
                lightProj = new Matrix4f().setPerspective(SILHOUETTE_FOV_RAD, 1.0f, 0.1f, 2000.0f);
                lightView = new Matrix4f().setLookAt(
                        ax - 12.0f * projectedSize * lightDir.x,
                        ay - 12.0f * projectedSize * lightDir.y,
                        az - 12.0f * projectedSize * lightDir.z,
                        ax, ay, az,
                        0.0f, -1.0f, 0.0f
                );
            } else {
                projMode = 0;
                sizeParam = (projectedSize * (5.0f / 9.0f)) / 3.0f;
            }

            prepared.add(new PreparedShadow(
                    actor,
                    projMode,
                    assignedLayer,
                    new Vector3f(ax, ay, az),
                    lightDir,
                    sizeParam,
                    lightProj,
                    lightView,
                    camDistSq,
                    tris
            ));
        }

        // 1. Render flat-white skinned silhouettes into silhouette array layers
        if (silLayer > 0 && characterShader != null) {
            glBindFramebuffer(GL_FRAMEBUFFER, silFboId);
            glViewport(0, 0, SILHOUETTE_RES, SILHOUETTE_RES);
            glDisable(GL_BLEND);
            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

            for (PreparedShadow ps : prepared) {
                if (ps.projMode() != 1) {
                    continue;
                }
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, silArrayTexId, 0, ps.layer());
                glClear(GL_COLOR_BUFFER_BIT);
                ps.actor().getVisualModel().renderSilhouette(
                        characterShader,
                        ps.lightProj(),
                        ps.lightView(),
                        ps.actor().getX(),
                        ps.actor().getY(),
                        ps.actor().getZ(),
                        ps.actor().getYaw()
                );
            }
            glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
            glViewport(0, 0, viewportWidth, viewportHeight);
        }

        // 2. Project silhouettes / blobs / quads onto ground geometry with dst * (1 - src)
        Matrix4f viewProj = new Matrix4f(projMatrix).mul(viewMatrix);

        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, viewportWidth, viewportHeight);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBlendFuncSeparate(GL_ZERO, GL_ONE_MINUS_SRC_COLOR, GL_ZERO, GL_ONE);

        glUseProgram(programId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(uViewProjLoc, false, viewProj.get(stack.mallocFloat(16)));
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, kageTexId);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, silArrayTexId);
        glActiveTexture(GL_TEXTURE0);

        for (PreparedShadow ps : prepared) {
            glUniform1i(uProjModeLoc, ps.projMode());
            glUniform3f(uActorPosLoc, ps.actorPos().x, ps.actorPos().y, ps.actorPos().z);
            glUniform3f(uLightDirLoc, ps.lightDir().x, ps.lightDir().y, ps.lightDir().z);
            glUniform1f(uSizeParamLoc, ps.sizeParam());
            glUniform1i(uLayerLoc, ps.layer());

            if (ps.projMode() == 2) {
                drawFlatQuad(ps.actorPos(), ps.sizeParam());
            } else {
                drawTriangles(ps.triangles());
            }
        }

        glBindVertexArray(0);
        glUseProgram(0);
        glDisable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glFrontFace(GL_CCW);
    }

    private void drawTriangles(List<CollisionTriangle> triangles) {
        int vertexCount = triangles.size() * 3;
        if (vertexCount == 0) {
            return;
        }
        int neededFloats = vertexCount * 3;
        if (triScratch.capacity() < neededFloats) {
            triScratch = BufferUtils.createFloatBuffer(Math.max(neededFloats, triScratch.capacity() * 2));
        }
        triScratch.clear();
        for (CollisionTriangle tri : triangles) {
            putTriVertex(triScratch, tri.v0());
            putTriVertex(triScratch, tri.v1());
            putTriVertex(triScratch, tri.v2());
        }
        triScratch.flip();

        glBindVertexArray(triVaoId);
        glBindBuffer(GL_ARRAY_BUFFER, triVboId);
        if (neededFloats > triVboCapacityFloats) {
            triVboCapacityFloats = Math.max(neededFloats, triVboCapacityFloats * 2);
            glBufferData(GL_ARRAY_BUFFER, (long) triVboCapacityFloats * Float.BYTES, GL_DYNAMIC_DRAW);
        }
        glBufferSubData(GL_ARRAY_BUFFER, 0L, triScratch);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    }

    private void drawFlatQuad(Vector3f pos, float sb) {
        float y = pos.y - 0.001f;
        float x0 = pos.x - sb;
        float x1 = pos.x + sb;
        float z0 = pos.z - sb;
        float z1 = pos.z + sb;

        quadScratch.clear();
        quadScratch.put(x0).put(y).put(z1);
        quadScratch.put(x1).put(y).put(z1);
        quadScratch.put(x0).put(y).put(z0);
        quadScratch.put(x1).put(y).put(z1);
        quadScratch.put(x1).put(y).put(z0);
        quadScratch.put(x0).put(y).put(z0);
        quadScratch.flip();

        glBindVertexArray(quadVaoId);
        glBindBuffer(GL_ARRAY_BUFFER, quadVboId);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, quadScratch);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private static void putTriVertex(FloatBuffer buf, com.example.asset.geometry.Vector3f v) {
        buf.put(v.x()).put(v.y()).put(v.z());
    }

    /** Matches {@code shadow.rs:99-142}: strongest in-range floor lamp else directional light, clamped >= 60 deg. */
    private static Vector3f resolveShadowLightDirection(
            float ax,
            float ay,
            float az,
            CollisionTransform floorTx,
            List<ZonePointLight> pointLights,
            float[] pointLightIntensities,
            Vector3f directionalLightDir
    ) {
        float bestWeight = 0.0f;
        Vector3f l0 = null;

        if (floorTx != null && pointLights != null && !pointLights.isEmpty()) {
            for (int slot = 0; slot < 4; slot++) {
                int idx = floorTx.pointLightIndex(slot);
                if (idx < 0 || idx >= pointLights.size()) {
                    continue;
                }
                ZonePointLight pl = pointLights.get(idx);
                float dx = pl.position().x - ax;
                float dy = pl.position().y - ay;
                float dz = pl.position().z - az;
                float d2 = dx * dx + dy * dy + dz * dz;
                if (pl.range() <= 0.0f || Math.sqrt(d2) > pl.range()) {
                    continue;
                }
                float intensity = (pointLightIntensities != null && idx < pointLightIntensities.length)
                        ? pointLightIntensities[idx]
                        : 0.0f;
                float att2 = intensity > 0.0f ? (1.0f / intensity) : 1.0f;
                float wt = 1.0f / Math.max(d2 * att2, 1e-4f);
                if (wt > bestWeight) {
                    bestWeight = wt;
                    l0 = new Vector3f(dx, dy, dz);
                }
            }
        }

        if (l0 == null) {
            l0 = (directionalLightDir != null && directionalLightDir.lengthSquared() > 1e-6f)
                    ? new Vector3f(directionalLightDir)
                    : new Vector3f(0.0f, -1.0f, 0.0f);
        }

        if (l0.y > 0.0f) {
            l0.set(0.45f, -0.9f, 0.0f);
        }
        l0.normalize();
        float h = (float) Math.hypot(l0.x, l0.z);
        if (h <= 1e-4f) {
            l0.set(0.01f, -1.0f, 0.0f).normalize();
        } else if (l0.y >= 0.0f || -Math.atan2(l0.y, h) < 1.0471976f) {
            l0.y = -0.8660254f * h;
            l0.normalize();
        }
        if (Math.abs(l0.x) < 0.01f && Math.abs(l0.z) < 0.01f) {
            l0.x = 0.01f;
            l0.normalize();
        }
        return l0;
    }

    /** Matches {@code shadow.rs:144-155}: actor bounding box projected onto light view plane. */
    private static float computeProjectedShadowSize(
            float ax,
            float ay,
            float az,
            Vector3f lf,
            AuthenticCharacterInstance.WorldBounds b
    ) {
        Matrix4f lightRot = new Matrix4f().setLookAt(
                ax - lf.x, ay - lf.y, az - lf.z,
                ax, ay, az,
                0.0f, -1.0f, 0.0f
        );
        float[] xs = {b.minX(), b.maxX()};
        float[] ys = {b.minY(), b.maxY()};
        float[] zs = {b.minZ(), b.maxZ()};
        float loX = Float.POSITIVE_INFINITY;
        float loY = Float.POSITIVE_INFINITY;
        float hiX = Float.NEGATIVE_INFINITY;
        float hiY = Float.NEGATIVE_INFINITY;
        Vector3f tmp = new Vector3f();
        for (float x : xs) {
            for (float y : ys) {
                for (float z : zs) {
                    tmp.set(x, y, z);
                    lightRot.transformPosition(tmp);
                    loX = Math.min(loX, tmp.x);
                    loY = Math.min(loY, tmp.y);
                    hiX = Math.max(hiX, tmp.x);
                    hiY = Math.max(hiY, tmp.y);
                }
            }
        }
        float size = Math.max(hiX - loX, hiY - loY);
        return Float.isFinite(size) && size > 0.01f ? size : 1.0f;
    }

    private static List<CollisionTriangle> collectNearbyShadowTriangles(
            CollisionMap collisionMap,
            float ax,
            float ay,
            float az,
            float projectedSize,
            boolean silhouetteMode
    ) {
        List<CollisionObjectGroup> groups = collisionMap.getNearbyGroups(
                new com.example.asset.geometry.Vector3f(ax, ay, az), 1
        );
        if (groups.isEmpty()) {
            return List.of();
        }
        float maxRangeXZ = Math.max(3.0f, projectedSize * 3.0f);
        float maxRangeY = 2.5f;
        Set<CollisionObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<CollisionTriangle> result = new ArrayList<>(64);
        for (CollisionObjectGroup group : groups) {
            for (CollisionObject obj : group.collisionObjects()) {
                if (obj == null || !seen.add(obj)) {
                    continue;
                }
                for (CollisionTriangle tri : obj.worldSpaceTriangles()) {
                    if (!silhouetteMode && tri.flags().isHitWall()) {
                        continue;
                    }
                    if (!triangleOverlapsActorShadow(tri, ax, ay, az, maxRangeXZ, maxRangeY)) {
                        continue;
                    }
                    result.add(tri);
                }
            }
        }
        return result;
    }

    private static boolean triangleOverlapsActorShadow(
            CollisionTriangle tri,
            float ax,
            float ay,
            float az,
            float maxRangeXZ,
            float maxRangeY
    ) {
        com.example.asset.geometry.Vector3f v0 = tri.v0();
        com.example.asset.geometry.Vector3f v1 = tri.v1();
        com.example.asset.geometry.Vector3f v2 = tri.v2();
        float minX = Math.min(v0.x(), Math.min(v1.x(), v2.x()));
        float maxX = Math.max(v0.x(), Math.max(v1.x(), v2.x()));
        if (maxX < ax - maxRangeXZ || minX > ax + maxRangeXZ) {
            return false;
        }
        float minZ = Math.min(v0.z(), Math.min(v1.z(), v2.z()));
        float maxZ = Math.max(v0.z(), Math.max(v1.z(), v2.z()));
        if (maxZ < az - maxRangeXZ || minZ > az + maxRangeXZ) {
            return false;
        }
        float minY = Math.min(v0.y(), Math.min(v1.y(), v2.y()));
        float maxY = Math.max(v0.y(), Math.max(v1.y(), v2.y()));
        return !(maxY < ay - maxRangeY || minY > ay + maxRangeY);
    }

    private static float distSq(Vector3f cameraPos, float x, float y, float z) {
        float dx = cameraPos.x - x;
        float dy = cameraPos.y - y;
        float dz = cameraPos.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int[] createKageTexture(Path ffxiRoot, FileTableManager existingTable) {
        try {
            Path kageDatPath = resolveKageDatPath(ffxiRoot, existingTable);
            if (kageDatPath != null && Files.isRegularFile(kageDatPath)) {
                byte[] rawData = Files.readAllBytes(kageDatPath);
                DatResourceGraph graph = DatParser.parseResourceGraph(
                        new ByteReader(rawData, kageDatPath.getFileName().toString())
                );
                TextureResource kageTex = findTexture(graph.rootDirectory());
                if (kageTex != null) {
                    byte[] rgba = TextureDecoder.decode(rawData, kageTex);
                    if (rgba != null) {
                        ByteBuffer buf = BufferUtils.createByteBuffer(rgba.length);
                        buf.put(rgba).flip();
                        int texId = uploadKageGlTexture(buf, kageTex.width(), kageTex.height());
                        log.info("Loaded retail system kage_tex ({}x{}) from {}",
                                kageTex.width(), kageTex.height(), kageDatPath);
                        return new int[]{texId, 1};
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Unable to decode retail kage_tex from 0x{}: {}",
                    Integer.toHexString(KAGE_FILE_ID), e.getMessage());
        }

        // Clean fallback radial shadow blob only when retail install is absent (e.g., synthetic smoke tests)
        int res = 32;
        ByteBuffer fallback = BufferUtils.createByteBuffer(res * res * 4);
        for (int y = 0; y < res; y++) {
            float dy = (y + 0.5f) / res * 2.0f - 1.0f;
            for (int x = 0; x < res; x++) {
                float dx = (x + 0.5f) / res * 2.0f - 1.0f;
                float r = (float) Math.hypot(dx, dy);
                float v = Math.max(0.0f, 1.0f - r);
                byte b = (byte) Math.min(255, Math.round(v * v * 255.0f));
                fallback.put(b).put(b).put(b).put((byte) 255);
            }
        }
        fallback.flip();
        return new int[]{uploadKageGlTexture(fallback, res, res), 0};
    }

    private static Path resolveKageDatPath(Path ffxiRoot, FileTableManager existingTable) {
        try {
            if (existingTable != null) {
                Path p = existingTable.getFileNioPath(KAGE_FILE_ID);
                if (p != null && Files.isRegularFile(p)) {
                    return p;
                }
            }
            if (ffxiRoot != null && Files.isDirectory(ffxiRoot)) {
                FileTableManager ftm = new FileTableManager();
                ftm.loadFromDirectory(ffxiRoot.toFile());
                Path p = ftm.getFileNioPath(KAGE_FILE_ID);
                if (p != null && Files.isRegularFile(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        Optional<Path> fallback = FfxiPathConfig.resolveRomFile("ROM/27/81.DAT");
        return fallback.orElse(null);
    }

    private static TextureResource findTexture(DirectoryResource dir) {
        if (dir == null) {
            return null;
        }
        TextureResource firstAny = null;
        for (ResourceNode child : dir.children()) {
            if (child instanceof TextureResource tex) {
                String name = tex.textureName() != null ? tex.textureName().toLowerCase(Locale.ROOT) : "";
                if (name.contains("kage_tex")) {
                    return tex;
                }
                if (firstAny == null) {
                    firstAny = tex;
                }
            } else if (child instanceof DirectoryResource subDir) {
                TextureResource found = findTexture(subDir);
                if (found != null) {
                    return found;
                }
            }
        }
        return firstAny;
    }

    private static int uploadKageGlTexture(ByteBuffer rgba, int width, int height) {
        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{0f, 0f, 0f, 0f});
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glBindTexture(GL_TEXTURE_2D, 0);
        return texId;
    }

    @Override
    public void close() {
        glDeleteBuffers(triVboId);
        glDeleteVertexArrays(triVaoId);
        glDeleteBuffers(quadVboId);
        glDeleteVertexArrays(quadVaoId);
        glDeleteFramebuffers(silFboId);
        glDeleteTextures(silArrayTexId);
        glDeleteTextures(kageTexId);
        glDeleteProgram(programId);
    }
}
