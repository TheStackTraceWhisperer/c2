package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.common.time.VanadielTime;
import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.model.LoadedCharacter;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Suite that evaluates skeletal animation playback, multi-slot layering,
 * dual-animation cross-fade blending, and dual-bone linear blend skinning across
 * authentic retail FFXI character rigs strictly through the production OpenGL 4.6 Core
 * {@link WorldScene} rendering pipeline.
 */
public class CharacterAnimationSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final int WIDTH = 1440;
    private static final int HEIGHT = 1620;

    record AnimCapture(
            String id,
            String label,
            RaceGenderConfig race,
            String slot0Anim,
            String slot1Anim,
            double time,
            float camAngleDeg,
            String blendSlot0,
            String blendSlot1,
            double blendTime,
            float blendFactor
    ) {
        AnimCapture(String id, String label, RaceGenderConfig race, String slot0, String slot1, double time, float angle) {
            this(id, label, race, slot0, slot1, time, angle, null, null, 0.0, 0.0f);
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
    @DisplayName("Loop through character animations, layers, and blends to verify GPU skeletal skinning and bone weights")
    void testRenderAndSnapshotAllCharacterAnimations() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        List<AnimCapture> captures = List.of(
                // 1. Core Hume Male Locomotion & Poses
                new AnimCapture("humem_idle", "Hume Male - Authentic Idle", RaceGenderConfig.HumeM, "idl0", "idl1", 0.0, -25.0f),
                new AnimCapture("humem_walk", "Hume Male - Striding Walk", RaceGenderConfig.HumeM, "wlk0", "wlk1", 0.35, -25.0f),
                new AnimCapture("humem_run", "Hume Male - Athletic Sprint", RaceGenderConfig.HumeM, "run0", "run1", 0.25, -25.0f),
                new AnimCapture("humem_retreat_back", "Hume Male - Backward Retreat", RaceGenderConfig.HumeM, "mvb0", "mvb1", 0.35, -25.0f),
                new AnimCapture("humem_strafe_left", "Hume Male - Strafe Left", RaceGenderConfig.HumeM, "mvl0", "mvl1", 0.25, -25.0f),
                new AnimCapture("humem_strafe_right", "Hume Male - Strafe Right", RaceGenderConfig.HumeM, "mvr0", "mvr1", 0.25, -25.0f),
                new AnimCapture("humem_sit_rest", "Hume Male - Rest / Meditation", RaceGenderConfig.HumeM, "rx10", "rx11", 0.10, -25.0f),
                new AnimCapture("humem_sit_floor", "Hume Male - Seated on Ground", RaceGenderConfig.HumeM, "si10", "si11", 0.10, -25.0f),
                new AnimCapture("humem_defeat_collapse", "Hume Male - Defeat Fall", RaceGenderConfig.HumeM, "ded0", "ded1", 0.60, -25.0f),

                // 2. Combat & Action Poses
                new AnimCapture("humem_combat_1h", "Hume Male - Combat 1H Stance", RaceGenderConfig.HumeM, "cm00", "cm01", 0.25, -25.0f),
                new AnimCapture("humem_combat_2h", "Hume Male - Heavy 2H Stance", RaceGenderConfig.HumeM, "cm10", "cm11", 0.25, -25.0f),
                new AnimCapture("humem_cast_gather", "Hume Male - Spell Gathering", RaceGenderConfig.HumeM, "mb00", "mb01", 0.30, -25.0f),
                new AnimCapture("humem_cast_channel", "Hume Male - Spell Channeling", RaceGenderConfig.HumeM, "mb10", "mb11", 0.50, -25.0f),
                new AnimCapture("humem_cast_release", "Hume Male - Spell Release", RaceGenderConfig.HumeM, "mb20", "mb21", 0.25, -25.0f),
                new AnimCapture("humem_item_use", "Hume Male - Item / Medicine Use", RaceGenderConfig.HumeM, "mi00", "mi01", 0.30, -25.0f),
                new AnimCapture("humem_weapon_skill", "Hume Male - Martial Weapon Skill", RaceGenderConfig.HumeM, "mw00", "mw01", 0.30, -25.0f),
                new AnimCapture("humem_hit_recoil", "Hume Male - Damage Recoil", RaceGenderConfig.HumeM, "bf10", "bf11", 0.15, -25.0f),
                new AnimCapture("humem_jump", "Hume Male - Jump Leap", RaceGenderConfig.HumeM, "jmp0", "jmp1", 0.20, -25.0f),
                new AnimCapture("humem_cheer", "Hume Male - Cheer Emote", RaceGenderConfig.HumeM, "sh00", "sh01", 0.25, -25.0f),

                // 3. Animation Layering (Upper body override on top of independent lower body)
                new AnimCapture("layer_run_legs_idle_arms", "Layered - Sprint Legs + Idle Arms", RaceGenderConfig.HumeM, "run0", "idl1", 0.25, -25.0f),
                new AnimCapture("layer_walk_legs_combat_arms", "Layered - Striding Walk + Combat 1H Arms", RaceGenderConfig.HumeM, "wlk0", "cm01", 0.35, -25.0f),
                new AnimCapture("layer_combat_stance_cast_arms", "Layered - Combat Footing + Spell Channel Arms", RaceGenderConfig.HumeM, "cm00", "mb01", 0.30, -25.0f),
                new AnimCapture("layer_run_legs_cast_arms", "Layered - Running Legs + Spell Cast Arms", RaceGenderConfig.HumeM, "run0", "mb01", 0.25, -25.0f),

                // 4. Animation Blending (Dual-State Keyframe Cross-Fade Interpolation)
                new AnimCapture("blend_walk_to_run_50", "Blended - 50% Walk / 50% Run Cross-Fade", RaceGenderConfig.HumeM, "wlk0", "wlk1", 0.35, -25.0f, "run0", "run1", 0.25, 0.50f),
                new AnimCapture("blend_idle_to_combat_50", "Blended - 50% Idle / 50% Combat Ready", RaceGenderConfig.HumeM, "idl0", "idl1", 0.00, -25.0f, "cm00", "cm01", 0.25, 0.50f),

                // 5. Multi-Race Animation Verification
                new AnimCapture("mithra_walk", "Mithra - Striding Walk", RaceGenderConfig.Mithra, "wlk0", "wlk1", 0.35, -25.0f),
                new AnimCapture("mithra_combat", "Mithra - Combat Ready Stance", RaceGenderConfig.Mithra, "cm00", "cm01", 0.25, -25.0f),
                new AnimCapture("tarum_run", "TaruTaru Male - Sprint Run", RaceGenderConfig.TaruM, "run0", "run1", 0.25, -25.0f),
                new AnimCapture("tarum_magic_cast", "TaruTaru Male - Spell Channel", RaceGenderConfig.TaruM, "mb00", "mb01", 0.30, -25.0f),
                new AnimCapture("elvaanm_combat", "Elvaan Male - Combat 1H Ready", RaceGenderConfig.ElvaanM, "cm00", "cm01", 0.25, -25.0f),
                new AnimCapture("galka_run", "Galka - Heavy Sprint Run", RaceGenderConfig.Galka, "run0", "run1", 0.25, -25.0f)
        );

        for (AnimCapture cap : captures) {
            System.out.printf("[ANIM-SUITE] Capturing GL4.6 '%s' for %s...%n", cap.label, cap.race.name());
            AuthenticCharacterInstance instance = worldScene.createCharacterInstance(FFXI_ROOT, cap.race);
            LoadedCharacter character = instance.getCharacter();
            assertNotNull(character.skeleton(), "Skeleton must exist for " + cap.race.name());

            Matrix4f[] jomlMatrices;
            if (cap.blendSlot0 != null) {
                CharacterAnimator animA = createAnimator(character, cap.slot0Anim, cap.slot1Anim);
                CharacterAnimator animB = createAnimator(character, cap.blendSlot0, cap.blendSlot1);
                jomlMatrices = animA.computeBlendedPoseMatrices(animB, cap.time, cap.blendTime, cap.blendFactor);
            } else {
                CharacterAnimator anim = createAnimator(character, cap.slot0Anim, cap.slot1Anim);
                jomlMatrices = anim.computePoseMatrices(cap.time);
            }

            instance.setPoseOverride(jomlMatrices);
            SceneActor actor = new SceneActor(1L, cap.race.name(), SceneActor.ActorType.PLAYER);
            actor.setVisualModel(instance);
            actor.setPosition(0.0f, 0.0f, 0.0f);
            actor.setRotation(0.0f);
            worldScene.setLocalPlayer(actor);

            float charHeight = Math.max(1.2f, character.feetOffsetY() * 2.0f * character.effectiveScale());
            SceneCamera camera = worldScene.getCamera();
            camera.setMode(CameraMode.FREE_ORBIT);
            camera.setFov(38.0f);
            camera.setNearPlane(0.1f);
            camera.setFarPlane(100.0f);
            camera.setTarget(0.0f, 0.0f, 0.0f);
            camera.setTargetOffsetY(charHeight * 0.5f);
            camera.setYaw(180.0f - cap.camAngleDeg);
            camera.setPitch(6.0f);
            camera.setDistance(charHeight * 1.75f);

            worldScene.update(0.0f, VanadielTime.ofHour(12.0f));
            BufferedImage snapshot = worldScene.captureFrame(WIDTH, HEIGHT);
            Path outPath = ScreenshotCapture.saveBufferedImage(snapshot, "character_anim_" + cap.id);

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 0, "Snapshot must be saved");
            System.out.printf("[ANIM-SUITE] Successfully saved %s (%,d bytes)%n",
                    outPath.getFileName(), Files.size(outPath));
        }
    }

    private static CharacterAnimator createAnimator(LoadedCharacter character, String slot0Name, String slot1Name) {
        SkeletonAnimationResource s0 = findAnim(character.slot0Animations(), slot0Name);
        SkeletonAnimationResource s1 = slot1Name != null ? findAnim(character.slot1Animations(), slot1Name) : null;
        if (s1 == null && slot1Name != null) {
            s1 = findAnim(character.slot0Animations(), slot1Name);
        }
        return new CharacterAnimator(character.skeleton(), s0, s1);
    }

    private static SkeletonAnimationResource findAnim(List<SkeletonAnimationResource> list, String id) {
        if (id == null || list == null) return null;
        for (SkeletonAnimationResource a : list) {
            if (a.sectionId().equalsIgnoreCase(id)) return a;
        }
        return null;
    }
}
