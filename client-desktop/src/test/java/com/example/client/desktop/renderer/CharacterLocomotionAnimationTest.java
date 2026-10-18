package com.example.client.desktop.renderer;

import com.example.asset.geometry.Matrix4f;
import com.example.asset.skeleton.AnimationState;
import com.example.asset.skeleton.AnimationStateMachine;
import com.example.asset.skeleton.BlendedPose;
import com.example.asset.skeleton.CharacterMeshGenerator;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.HumanoidRig;
import com.example.asset.skeleton.Skeleton;
import com.example.asset.skeleton.StandardAnimations;
import com.example.common.protocol.JobId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterLocomotionAnimationTest {

    private static final float EPSILON = 1e-4f;

    @Test
    @DisplayName("AnimationStateMachine transitions smoothly between IDLE, WALK, and RUN")
    void testLocomotionStateTransitions() {
        AnimationStateMachine asm = StandardAnimations.createLocomotionStateMachine();
        assertEquals(AnimationState.IDLE, asm.getCurrentState());

        // Zero speed with no movement flags -> IDLE
        asm.update(0.1f, 0, 0.0f);
        assertEquals(AnimationState.IDLE, asm.getCurrentState());

        // Walking forward at 2.5 m/s
        asm.update(0.05f, 1, 2.5f);
        assertEquals(AnimationState.WALK, asm.getTargetState());
        assertTrue(asm.isTransitioning());

        // Advance past transition duration (150ms)
        asm.update(0.2f, 1, 2.5f);
        assertEquals(AnimationState.WALK, asm.getCurrentState());
        assertFalse(asm.isTransitioning());

        // Sprinting forward at 6.0 m/s -> RUN
        asm.update(0.05f, 1, 6.0f);
        assertEquals(AnimationState.RUN, asm.getTargetState());
        asm.update(0.2f, 1, 6.0f);
        assertEquals(AnimationState.RUN, asm.getCurrentState());
    }

    @Test
    @DisplayName("Blended forward kinematics evaluates 21 bone matrices for humanoid skeleton")
    void testSkeletonPoseEvaluation() {
        Skeleton skeleton = HumanoidRig.createDefaultSkeleton();
        assertEquals(HumanoidRig.BONE_COUNT, skeleton.boneCount());

        AnimationStateMachine asm = StandardAnimations.createLocomotionStateMachine();
        asm.update(0.033f, 1, 3.0f); // Walk pose

        BlendedPose pose = asm.evaluate(skeleton);
        assertNotNull(pose);
        List<Matrix4f> matrices = pose.jointPoseMatrices();
        assertEquals(HumanoidRig.BONE_COUNT, matrices.size(), "Should evaluate exactly 21 joint matrices");

        // Verify root bone has translation around hips (~0.95m)
        Matrix4f rootMat = matrices.get(HumanoidRig.BONE_ROOT);
        assertNotNull(rootMat);
        assertTrue(rootMat.get(13) > 0.8f && rootMat.get(13) < 1.1f, "Root Y should be near hip height");

        // Verify head bone has translation around eye height (~1.69m)
        Matrix4f headMat = matrices.get(HumanoidRig.BONE_HEAD);
        assertNotNull(headMat);
        assertTrue(headMat.get(13) > 1.5f && headMat.get(13) < 1.9f, "Head Y should be near eye height");
    }

    @Test
    @DisplayName("In bind pose, skinning matrices S_i = B_i * InvBind_i equal identity matrix")
    void testBindPoseSkinningMatricesEqualIdentity() {
        Skeleton skeleton = HumanoidRig.createDefaultSkeleton();
        List<Matrix4f> bindPose = skeleton.computeBindPoseMatrices();
        List<Matrix4f> skinning = skeleton.computeSkinningMatrices(bindPose);

        assertEquals(HumanoidRig.BONE_COUNT, skinning.size());

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
    @DisplayName("Composite character models are generated with valid equipment slots for player jobs and NPCs")
    void testCompositeModelsForJobs() {
        short[] testJobs = new short[]{
                JobId.WARRIOR,
                JobId.PALADIN,
                JobId.WHITE_MAGE,
                JobId.BLACK_MAGE,
                JobId.RED_MAGE,
                JobId.THIEF,
                JobId.NPC
        };

        for (short jobId : testJobs) {
            CompositeCharacterModel model = CharacterMeshGenerator.createCharacterModel(jobId);
            assertNotNull(model, "Model should be generated for job " + jobId);
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HEAD));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HANDS));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_LEGS));
            assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_FEET));

            if (jobId == JobId.PALADIN) {
                assertTrue(model.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_SUB),
                        "Paladin should have shield sub-slot equipped");
            }
        }
    }
}
