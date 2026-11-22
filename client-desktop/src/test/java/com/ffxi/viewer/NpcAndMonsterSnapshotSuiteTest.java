package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneCamera;
import com.example.common.protocol.MovementFlags;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshVertex;
import com.ffxi.model.Vec3f;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.renderer.CharacterShader;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Suite that loads authentic retail FFXI monsters and non-player characters (NPCs)
 * from game ROM DAT archives via {@link CharacterModelLoader#loadStandaloneDat(Path)} and renders
 * GPU-skinned OpenGL 4.6 Core snapshots via {@link AuthenticCharacterInstance} and {@link CharacterShader}.
 */
public class NpcAndMonsterSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final int WIDTH = 1440;
    private static final int HEIGHT = 1620;

    record EntityTarget(
            String id,
            String displayName,
            String category,
            String datPath,
            float camAngleDeg,
            float distMult,
            float yOffsetRatio
    ) {
        EntityTarget(String id, String displayName, String category, String datPath) {
            this(id, displayName, category, datPath, 25.0f, 1.55f, 0.0f);
        }

        EntityTarget(String id, String displayName, String category, String datPath, float camAngleDeg, float distMult) {
            this(id, displayName, category, datPath, camAngleDeg, distMult, 0.0f);
        }
    }

    private static HeadlessGlContext glContext;
    private static CharacterShader characterShader;

    @BeforeAll
    static void initSuite() {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create();
        characterShader = new CharacterShader();
    }

    @AfterAll
    static void tearDownSuite() {
        if (characterShader != null) {
            characterShader.close();
        }
        if (glContext != null) {
            glContext.close();
        }
    }

    @Test
    @DisplayName("Render and snapshot authentic retail FFXI monsters and NPCs")
    void testRenderAndSnapshotAllMonstersAndNpcs() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        List<EntityTarget> targets = List.of(
                // --- Iconic Monsters ---
                new EntityTarget("monster_mandragora", "Mandragora", "Monster", "ROM/4/127.DAT", 25.0f, 1.60f),
                new EntityTarget("monster_goblin", "Goblin", "Monster", "ROM/3/59.DAT", 20.0f, 1.50f),
                new EntityTarget("monster_chocobo", "Chocobo", "Monster", "ROM/3/60.DAT", 35.0f, 1.65f),
                new EntityTarget("monster_rabbit", "Forest Rabbit", "Monster", "ROM/4/108.DAT", 25.0f, 1.55f),
                new EntityTarget("monster_crawler", "Crawler", "Monster", "ROM/5/50.DAT", 30.0f, 1.60f),
                new EntityTarget("monster_skeleton", "Skeleton Warrior", "Monster", "ROM/5/29.DAT", 20.0f, 1.55f),
                new EntityTarget("monster_sheep", "Wild Sheep", "Monster", "ROM/5/19.DAT", 30.0f, 1.55f),
                new EntityTarget("monster_cactuar", "Cactuar (Sabotender)", "Monster", "ROM/5/38.DAT", 20.0f, 1.55f),

                // --- Iconic Non-Player Characters (NPCs) ---
                new EntityTarget("npc_moogle", "Moogle", "NPC", "ROM/3/56.DAT", 20.0f, 1.55f),
                new EntityTarget("npc_lion", "Lion (Heroine)", "NPC", "ROM/3/34.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_cid", "Cid (Bastok Chief Engineer)", "NPC", "ROM/3/37.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_child_humem", "Hume Child (Boy)", "NPC", "ROM/3/88.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_child_humef", "Hume Child (Girl)", "NPC", "ROM/3/93.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_town_guard", "Town Guard (Elvaan)", "NPC", "ROM/3/116.DAT", 20.0f, 1.50f)
        );

        for (EntityTarget target : targets) {
            Path datFile = FFXI_ROOT.resolve(target.datPath());
            assertTrue(Files.exists(datFile), "Retail DAT must exist: " + datFile);

            LoadedCharacter loaded = CharacterModelLoader.loadStandaloneDat(datFile);
            assertNotNull(loaded, "Loaded character must not be null for " + target.displayName());
            assertFalse(loaded.meshes().isEmpty(), "Mesh must contain submeshes for " + target.displayName());

            try (AuthenticCharacterInstance instance = new AuthenticCharacterInstance(loaded)) {
                instance.update(0.0f, MovementFlags.IDLE, 0.0f);
                Matrix4f[] jointMatrices = instance.getLocomotion().computePoseMatrices();

                float scale = loaded.effectiveScale();
                Matrix4f modelMat = new Matrix4f()
                        .translate(0.0f, -loaded.feetLocalY() * scale, 0.0f)
                        .rotateY((float) Math.toRadians(-90.0f))
                        .scale(scale);
                WorldBounds bounds = computePosedWorldBounds(loaded, jointMatrices, modelMat);

                float centerX = 0.5f * (bounds.minX() + bounds.maxX());
                float centerY = 0.5f * (bounds.minY() + bounds.maxY());
                float centerZ = 0.5f * (bounds.minZ() + bounds.maxZ());
                float poseWidth = Math.max(0.1f, bounds.maxX() - bounds.minX());
                float poseHeight = Math.max(0.1f, bounds.maxY() - bounds.minY());
                float poseDepth = Math.max(0.1f, bounds.maxZ() - bounds.minZ());
                float horizExtent = Math.max(poseWidth, poseDepth);

                float aspect = (float) WIDTH / (float) HEIGHT;
                float tanHalfFovY = (float) Math.tan(Math.toRadians(38.0 * 0.5));
                float tanHalfFovX = tanHalfFovY * aspect;
                float distV = (poseHeight * 0.5f) / (tanHalfFovY * 0.74f);
                float distH = (horizExtent * 0.5f) / (tanHalfFovX * 0.76f);
                float dist = (Math.max(distV, distH) + 0.30f * horizExtent) * (target.distMult() / 1.55f);

                SceneCamera camera = new SceneCamera();
                camera.setMode(CameraMode.FREE_ORBIT);
                camera.setFov(38.0f);
                camera.setNearPlane(0.1f);
                camera.setFarPlane(100.0f);
                camera.setTarget(centerX, centerY, centerZ);
                camera.setTargetOffsetY(poseHeight * target.yOffsetRatio());
                camera.setYaw(180.0f - target.camAngleDeg());
                camera.setPitch(6.0f);
                camera.setDistance(Math.max(1.0f, dist));

                Matrix4f proj = camera.getProjectionMatrix(aspect);
                Matrix4f view = camera.getViewMatrix();

                System.out.printf("[SNAPSHOT-SUITE] Rendering GL4.6 %s [%s] (%s): %d bones, %d meshes, %d textures, scale=%.2f, center=(%.2f,%.2f,%.2f), size=(%.2fx%.2fx%.2f), dist=%.2f%n",
                        target.category(), target.displayName(), target.datPath(),
                        loaded.skeleton().joints().size(), loaded.meshes().size(), loaded.textures().size(),
                        scale, centerX, centerY, centerZ, poseWidth, poseHeight, poseDepth, camera.getDistance());

                Path outPath = ScreenshotCapture.captureOffscreen(WIDTH, HEIGHT, target.id(), () -> {
                    GL11.glClearColor(20.0f / 255.0f, 24.0f / 255.0f, 36.0f / 255.0f, 1.0f);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    instance.renderPose(characterShader, proj, view, jointMatrices, 0.0f, 0.0f, 0.0f, 0.0f);
                });

                assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 0,
                        "Snapshot file must be written for " + target.id());
                System.out.printf("[SNAPSHOT-SUITE] Saved snapshot -> %s (%,d bytes)%n",
                        outPath.getFileName(), Files.size(outPath));
            }
        }
    }

    private record WorldBounds(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
    }

    private static WorldBounds computePosedWorldBounds(
            LoadedCharacter loaded,
            Matrix4f[] joints,
            Matrix4f modelMat
    ) {
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        if (joints != null && joints.length > 0) {
            Vector4f v0 = new Vector4f();
            Vector4f v1 = new Vector4f();
            for (SkeletonMeshMesh mesh : loaded.meshes()) {
                for (SkeletonMeshVertex v : mesh.resolvedVertices()) {
                    float w0 = v.joint0Weight();
                    int j0 = clampJointIndex(v.jointIndex0(), joints.length);
                    int j1 = clampJointIndex(v.jointIndex1(), joints.length);

                    Vec3f p0 = v.p0();
                    Vec3f p1 = v.p1();
                    float p0x = p0 != null ? p0.x() : 0.0f;
                    float p0y = p0 != null ? p0.y() : 0.0f;
                    float p0z = p0 != null ? p0.z() : 0.0f;
                    float p1x = p1 != null ? p1.x() : 0.0f;
                    float p1y = p1 != null ? p1.y() : 0.0f;
                    float p1z = p1 != null ? p1.z() : 0.0f;

                    v0.set(p0x, p0y, p0z, w0);
                    joints[j0].transform(v0);
                    v1.set(p1x, p1y, p1z, 1.0f - w0);
                    joints[j1].transform(v1);
                    v0.add(v1);
                    modelMat.transform(v0);

                    if (Float.isFinite(v0.x) && Float.isFinite(v0.y) && Float.isFinite(v0.z)) {
                        minX = Math.min(minX, v0.x);
                        maxX = Math.max(maxX, v0.x);
                        minY = Math.min(minY, v0.y);
                        maxY = Math.max(maxY, v0.y);
                        minZ = Math.min(minZ, v0.z);
                        maxZ = Math.max(maxZ, v0.z);
                    }
                }
            }
        }

        if (!Float.isFinite(minX)) {
            float scale = loaded.effectiveScale();
            float halfRadius = Math.max(0.25f, loaded.radius() * scale);
            float height = Math.max(0.5f, loaded.feetOffsetY() * 2.0f * scale);
            return new WorldBounds(-halfRadius, halfRadius, -height, 0.0f, -halfRadius, halfRadius);
        }
        return new WorldBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static int clampJointIndex(Integer jointIndex, int jointCount) {
        if (jointIndex == null || jointCount <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(jointCount - 1, jointIndex));
    }
}
