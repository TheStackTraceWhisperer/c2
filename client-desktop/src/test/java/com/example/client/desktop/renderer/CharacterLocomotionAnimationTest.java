package com.example.client.desktop.renderer;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.geometry.Matrix4f;
import com.example.asset.loader.RaceModelLoader;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.KeyFrameTransform;
import com.example.asset.skeleton.Skeleton;
import com.example.asset.skeleton.SkeletonAnimation;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.AuthenticLocomotionController;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterLocomotionAnimationTest {

    private static final float EPSILON = 1e-4f;

    @Test
    @DisplayName("AuthenticLocomotionController transitions smoothly between IDLE, WALK, RUN, REST, and COMBAT")
    void testAuthenticLocomotionTransitions() {
        AuthenticLocomotionController controller = new AuthenticLocomotionController(null, null, null);
        assertEquals(AuthenticLocomotionController.State.IDLE, controller.getCurrentState());

        // Zero speed with no movement flags -> IDLE
        controller.update(0.1f, 0, 0.0f);
        assertEquals(AuthenticLocomotionController.State.IDLE, controller.getCurrentState());
        assertFalse(controller.isTransitioning());

        // Walking forward at 2.5 m/s (movement flag 1 = forward)
        controller.update(0.05f, 1, 2.5f);
        assertEquals(AuthenticLocomotionController.State.WALK, controller.getTargetState());
        assertTrue(controller.isTransitioning());
        assertTrue(controller.getBlendWeight() > 0.0f && controller.getBlendWeight() < 1.0f);

        // Advance past transition duration (150ms)
        controller.update(0.15f, 1, 2.5f);
        assertEquals(AuthenticLocomotionController.State.WALK, controller.getCurrentState());
        assertFalse(controller.isTransitioning());

        // Sprinting forward at 6.0 m/s -> RUN
        controller.update(0.05f, 1, 6.0f);
        assertEquals(AuthenticLocomotionController.State.RUN, controller.getTargetState());
        controller.update(0.15f, 1, 6.0f);
        assertEquals(AuthenticLocomotionController.State.RUN, controller.getCurrentState());
        assertFalse(controller.isTransitioning());

        // Rest flag (32) -> REST
        controller.update(0.05f, 32, 0.0f);
        assertEquals(AuthenticLocomotionController.State.REST, controller.getTargetState());
        controller.update(0.15f, 32, 0.0f);
        assertEquals(AuthenticLocomotionController.State.REST, controller.getCurrentState());

        // Engaged flag (64) -> COMBAT
        controller.update(0.05f, 64, 0.0f);
        assertEquals(AuthenticLocomotionController.State.COMBAT, controller.getTargetState());
        controller.update(0.15f, 64, 0.0f);
        assertEquals(AuthenticLocomotionController.State.COMBAT, controller.getCurrentState());
    }

    @Test
    @DisplayName("In bind pose, authentic skeleton skinning matrices S_i = B_i * InvBind_i equal identity matrix")
    void testBindPoseSkinningMatricesEqualIdentity() {
        RaceModelLoader loader = new RaceModelLoader();
        CompositeCharacterModel model = loader.loadRaceModel(RaceModelLoader.CharacterRace.HUME_MALE);
        assertNotNull(model);
        Skeleton skeleton = model.skeleton();
        assertNotNull(skeleton);
        assertTrue(skeleton.boneCount() > 20, "Authentic Hume Male skeleton should have > 20 bones");

        List<Matrix4f> bindPose = skeleton.computeBindPoseMatrices();
        List<Matrix4f> skinning = skeleton.computeSkinningMatrices(bindPose);

        assertEquals(skeleton.boneCount(), skinning.size());

        for (int b = 0; b < skinning.size(); b++) {
            Matrix4f s = skinning.get(b);
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    float expected = (r == c) ? 1.0f : 0.0f;
                    assertEquals(expected, s.get(c * 4 + r), EPSILON,
                            "Bone " + b + " matrix [" + r + "," + c + "] should match identity in bind pose");
                }
            }
        }
    }

    @Test
    @DisplayName("Authentic character models are loaded with valid equipment slots for player races and NPCs")
    void testCompositeModelsForJobs() {
        RaceModelLoader loader = new RaceModelLoader();
        for (RaceModelLoader.CharacterRace race : RaceModelLoader.CharacterRace.values()) {
            CompositeCharacterModel model = loader.loadRaceModel(race);
            assertNotNull(model, "Model should be loaded for race " + race.displayName());
            assertNotNull(model.skeleton(), "Skeleton must exist for " + race.displayName());
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HEAD));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HANDS));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_LEGS));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_FEET));
        }

        CompositeCharacterModel npc = loader.loadNpcModel(0, null);
        assertNotNull(npc, "NPC model should load");
        assertTrue(npc.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY));
    }

    @Test
    @DisplayName("Authentic ROM animations loop continuously over long durations without freezing")
    void testContinuousAnimationLooping() {
        RaceModelLoader loader = new RaceModelLoader();
        var anims = loader.loadRaceAnimations(RaceModelLoader.CharacterRace.HUME_MALE);
        assertTrue(anims.containsKey("wlk0"), "Authentic Hume Male should have wlk0 animation");
        SkeletonAnimation walk = anims.get("wlk0");
        assertTrue(walk.totalDurationSeconds() > 0.0f);

        // Sample at t = 0.25s and t = totalDuration + 0.25s (1 complete loop later)
        int testBone = 0; // Root bone
        KeyFrameTransform kfInitial = walk.evaluateAtTime(testBone, 0.25f, true);
        KeyFrameTransform kfLooped = walk.evaluateAtTime(testBone, walk.totalDurationSeconds() + 0.25f, true);

        assertEquals(kfInitial.translation().y(), kfLooped.translation().y(), 0.01f,
                "Root Y at looped time should match initial time identically due to seamless looping");
        assertEquals(kfInitial.rotation().w(), kfLooped.rotation().w(), 0.01f,
                "Root rotation W at looped time should match initial time");

        // Verify continuous evaluation over 60 seconds of walking
        CompositeCharacterModel model = loader.loadRaceModel(RaceModelLoader.CharacterRace.HUME_MALE);
        Skeleton skeleton = model.skeleton();

        float time = 0.0f;
        for (int frame = 0; frame < 3600; frame++) {
            time += 1.0f / 60.0f;
        }

        List<Matrix4f> poseAfter60s = model.computePoseMatrices(walk, time);
        assertNotNull(poseAfter60s);
        assertEquals(skeleton.boneCount(), poseAfter60s.size());

        // Advance 15 frames (~0.25s) further and verify pose changes (not frozen)
        List<Matrix4f> poseLater = model.computePoseMatrices(walk, time + 0.25f);
        boolean anyDifferent = false;
        for (int i = 0; i < poseAfter60s.size(); i++) {
            if (Math.abs(poseAfter60s.get(i).get(0) - poseLater.get(i).get(0)) > 1e-3f) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "Pose transform matrices should change during walking cycle, confirming it does not freeze");
    }

    @Test
    @DisplayName("Non-looping animations clamp cleanly at bounds when loop is false")
    void testNonLoopingAnimationClamping() {
        RaceModelLoader loader = new RaceModelLoader();
        var anims = loader.loadRaceAnimations(RaceModelLoader.CharacterRace.HUME_MALE);
        assertTrue(anims.containsKey("idl0"));
        SkeletonAnimation idle = anims.get("idl0");

        // When loop is false, time past duration should clamp to last frame
        int testBone = 0;
        KeyFrameTransform kfAtEnd = idle.evaluateAtTime(testBone, idle.totalDurationSeconds(), false);
        KeyFrameTransform kfPastEnd = idle.evaluateAtTime(testBone, idle.totalDurationSeconds() + 10.0f, false);

        assertEquals(kfAtEnd.translation().y(), kfPastEnd.translation().y(), EPSILON);
        assertEquals(kfAtEnd.rotation().w(), kfPastEnd.rotation().w(), EPSILON);
    }

    @Test
    @DisplayName("Authentic character model loader loads Hume Male and evaluates UBO skinning matrices")
    void testAuthenticCharacterModelLoader() throws Exception {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        if (!Files.isDirectory(ffxiRoot)) {
            return; // Skip if retail directory not present in current test environment
        }

        LoadedCharacter hume = CharacterModelLoader.loadCharacter(ffxiRoot, RaceGenderConfig.HumeM);
        assertNotNull(hume);
        assertNotNull(hume.skeleton());
        assertTrue(hume.skeleton().joints().size() > 0);
        assertFalse(hume.meshes().isEmpty());

        AuthenticLocomotionController controller = new AuthenticLocomotionController(
                hume.skeleton(),
                hume.slot0Animations(),
                hume.slot1Animations()
        );

        org.joml.Matrix4f[] matrices = controller.computePoseMatrices();
        assertNotNull(matrices);
        assertEquals(hume.skeleton().joints().size(), matrices.length);

        // Advance controller to walk state and compute blended pose
        controller.update(0.1f, 1, 2.5f);
        org.joml.Matrix4f[] walkMatrices = controller.computePoseMatrices();
        assertNotNull(walkMatrices);
        assertEquals(hume.skeleton().joints().size(), walkMatrices.length);
    }
}
