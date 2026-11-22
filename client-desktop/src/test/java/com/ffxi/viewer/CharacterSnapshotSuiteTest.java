package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.common.time.VanadielTime;
import com.ffxi.tables.RaceGenderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Playable Race Character Portrait Snapshot Suite.
 * Renders idle postures for all 8 playable race/gender combinations using the production
 * OpenGL 4.6 Core {@link WorldScene} and {@link com.ffxi.viewer.renderer.CharacterShader} pipeline.
 */
public class CharacterSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final int WIDTH = 1440;
    private static final int HEIGHT = 1620;

    record RaceConfig(RaceGenderConfig race, String snapshotKey) {}

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
        worldScene.setClearColor(22.0f / 255.0f, 26.0f / 255.0f, 38.0f / 255.0f);
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
    @DisplayName("Render and snapshot authentic idle animation poses for all playable race and gender combinations")
    void testRenderAndSnapshotAllPlayableRaces() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        RaceConfig[] playableRaces = new RaceConfig[] {
                new RaceConfig(RaceGenderConfig.HumeM, "humem"),
                new RaceConfig(RaceGenderConfig.HumeF, "humef"),
                new RaceConfig(RaceGenderConfig.ElvaanM, "elvaanm"),
                new RaceConfig(RaceGenderConfig.ElvaanF, "elvaanf"),
                new RaceConfig(RaceGenderConfig.TaruM, "tarum"),
                new RaceConfig(RaceGenderConfig.TaruF, "taruf"),
                new RaceConfig(RaceGenderConfig.Mithra, "mithra"),
                new RaceConfig(RaceGenderConfig.Galka, "galka")
        };

        for (RaceConfig cfg : playableRaces) {
            System.out.println("[SNAPSHOT-TEST] Loading authentic character model: " + cfg.race.name());
            AuthenticCharacterInstance instance = worldScene.createCharacterInstance(FFXI_ROOT, cfg.race);
            assertNotNull(instance.getCharacter(), "Character should load for " + cfg.race.name());
            assertNotNull(instance.getCharacter().skeleton(), "Skeleton must exist for " + cfg.race.name());

            SceneActor actor = new SceneActor(1L, cfg.race.name(), SceneActor.ActorType.PLAYER);
            actor.setVisualModel(instance);
            actor.setPosition(0.0f, 0.0f, 0.0f);
            actor.setRotation(0.0f);
            worldScene.setLocalPlayer(actor);

            float charHeight = Math.max(0.6f, instance.getCharacter().feetOffsetY() * 2.0f * instance.getCharacter().effectiveScale());

            SceneCamera camera = worldScene.getCamera();
            camera.setMode(CameraMode.FREE_ORBIT);
            camera.setFov(38.0f);
            camera.setNearPlane(0.1f);
            camera.setFarPlane(100.0f);
            camera.setTarget(0.0f, 0.0f, 0.0f);
            camera.setTargetOffsetY(charHeight * 0.5f);
            camera.setYaw(155.0f);
            camera.setPitch(6.0f);
            camera.setDistance(charHeight * 1.65f);

            worldScene.update(0.0f, VanadielTime.ofHour(12.0f));
            BufferedImage snapshot = worldScene.captureFrame(WIDTH, HEIGHT);
            Path outPath = ScreenshotCapture.saveBufferedImage(snapshot, "character_idle_" + cfg.snapshotKey);

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 0, "Snapshot file should be written");
            System.out.printf("[SNAPSHOT-TEST] Saved GL4.6 %s to %s (%,d bytes)%n",
                    cfg.race.name(), outPath.getFileName(), Files.size(outPath));
        }
    }
}
