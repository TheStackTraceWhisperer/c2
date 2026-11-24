package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.common.protocol.MovementFlags;
import com.example.common.time.VanadielTime;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
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
 * GPU-skinned OpenGL 4.6 Core snapshots strictly through the production {@link WorldScene} pipeline.
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
    private static WorldScene worldScene;

    @BeforeAll
    static void initSuite() {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create();
        worldScene = new WorldScene();
        worldScene.init();
        assertTrue(worldScene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");

        worldScene.setShowSkyDome(false);
        worldScene.setEnableShadows(false);
        worldScene.setEnableSsao(false);
        worldScene.setClearColor(20.0f / 255.0f, 24.0f / 255.0f, 36.0f / 255.0f);
    }

    @AfterAll
    static void tearDownSuite() {
        if (worldScene != null) {
            worldScene.close();
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

            AuthenticCharacterInstance instance = new AuthenticCharacterInstance(loaded);
            SceneActor actor = new SceneActor(1L, target.displayName(), SceneActor.ActorType.NPC);
            actor.setVisualModel(instance);
            actor.setPosition(0.0f, 0.0f, 0.0f);
            actor.setRotation(0.0f);
            actor.setMovementFlags(MovementFlags.IDLE);
            actor.setMovementSpeed(0.0f);
            worldScene.setLocalPlayer(actor);

            worldScene.update(0.0f, VanadielTime.ofHour(12.0f));
            AuthenticCharacterInstance.WorldBounds bounds = instance.computePosedWorldBounds(0.0f, 0.0f, 0.0f, 0.0f);

            float scale = loaded.effectiveScale();
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

            SceneCamera camera = worldScene.getCamera();
            camera.setMode(CameraMode.FREE_ORBIT);
            camera.setFov(38.0f);
            camera.setNearPlane(0.1f);
            camera.setFarPlane(100.0f);
            camera.setTarget(centerX, centerY, centerZ);
            camera.setTargetOffsetY(poseHeight * target.yOffsetRatio());
            camera.setYaw(180.0f - target.camAngleDeg());
            camera.setPitch(6.0f);
            camera.setDistance(Math.max(1.0f, dist));

            System.out.printf("[SNAPSHOT-SUITE] Rendering GL4.6 %s [%s] (%s): %d bones, %d meshes, %d textures, scale=%.2f, center=(%.2f,%.2f,%.2f), size=(%.2fx%.2fx%.2f), dist=%.2f%n",
                    target.category(), target.displayName(), target.datPath(),
                    loaded.skeleton().joints().size(), loaded.meshes().size(), loaded.textures().size(),
                    scale, centerX, centerY, centerZ, poseWidth, poseHeight, poseDepth, camera.getDistance());

            BufferedImage snapshot = worldScene.captureFrame(WIDTH, HEIGHT);
            Path outPath = ScreenshotCapture.saveBufferedImage(snapshot, target.id());

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 0,
                    "Snapshot file must be written for " + target.id());
            System.out.printf("[SNAPSHOT-SUITE] Saved snapshot -> %s (%,d bytes)%n",
                    outPath.getFileName(), Files.size(outPath));
        }
    }
}
