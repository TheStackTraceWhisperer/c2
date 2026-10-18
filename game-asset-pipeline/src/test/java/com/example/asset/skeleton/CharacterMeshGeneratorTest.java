package com.example.asset.skeleton;

import com.example.asset.geometry.Matrix4f;
import com.example.common.protocol.JobId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterMeshGeneratorTest {

    @Test
    @DisplayName("CharacterMeshGenerator creates full multi-slot composite model with valid skinned weights")
    void testCreateWarriorModel() {
        CompositeCharacterModel warrior = CharacterMeshGenerator.createCharacterModel(JobId.WARRIOR);
        assertNotNull(warrior);
        assertNotNull(warrior.skeleton());
        assertEquals(HumanoidRig.BONE_COUNT, warrior.skeleton().boneCount());

        assertTrue(warrior.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HEAD));
        assertTrue(warrior.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_BODY));
        assertTrue(warrior.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_HANDS));
        assertTrue(warrior.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_LEGS));
        assertTrue(warrior.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_FEET));
        assertTrue(warrior.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_MAIN));

        // Total triangle count should be substantial (> 100 triangles)
        int totalTris = warrior.equipmentSlots().values().stream()
                .mapToInt(SkinnedMesh::totalTriangleCount)
                .sum();
        assertTrue(totalTris >= 100, "Character model should contain over 100 triangles, was: " + totalTris);

        // Verify vertex weights sum to 1.0 for all vertices
        for (SkinnedMesh mesh : warrior.equipmentSlots().values()) {
            for (SkinnedSubMesh sub : mesh.subMeshes()) {
                for (SkinnedVertex v : sub.vertices()) {
                    assertEquals(1.0f, v.jointWeight0() + v.jointWeight1(), 1e-5f);
                    assertTrue(v.jointIndex0() >= 0 && v.jointIndex0() < HumanoidRig.BONE_COUNT);
                    assertTrue(v.jointIndex1() >= 0 && v.jointIndex1() < HumanoidRig.BONE_COUNT);
                }
            }
        }
    }

    @Test
    @DisplayName("Paladin model has shield slot and White Mage model has staff")
    void testJobSpecificEquipment() {
        CompositeCharacterModel pld = CharacterMeshGenerator.createCharacterModel(JobId.PALADIN);
        assertTrue(pld.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_SUB), "PLD should have shield in sub slot");

        CompositeCharacterModel whm = CharacterMeshGenerator.createCharacterModel(JobId.WHITE_MAGE);
        assertFalse(whm.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_SUB), "WHM should not have shield");
        assertTrue(whm.equipmentSlots().containsKey(CompositeCharacterModel.SLOT_MAIN), "WHM should have staff");
    }

    @Test
    @DisplayName("StandardAnimations drive CompositeCharacterModel FK pose evaluation correctly")
    void testLocomotionAnimationEvaluation() {
        CompositeCharacterModel character = CharacterMeshGenerator.createCharacterModel(JobId.WARRIOR);
        SkeletonAnimation walkAnim = StandardAnimations.createWalkAnimation();
        assertNotNull(walkAnim);
        assertEquals(HumanoidRig.BONE_COUNT, walkAnim.numJoints());

        // Evaluate at frame 7.5 (halfway between frames 7 and 8)
        List<Matrix4f> pose = character.computePoseMatrices(walkAnim, 7.5f);
        assertEquals(HumanoidRig.BONE_COUNT, pose.size());

        for (Matrix4f m : pose) {
            assertNotNull(m);
            // Verify matrix has valid values (no NaN)
            for (int i = 0; i < 16; i++) {
                assertFalse(Float.isNaN(m.get(i)), "Bone matrix element must not be NaN");
            }
        }
    }

    @Test
    @DisplayName("AnimationStateMachine transitions seamlessly between IDLE, WALK, and RUN")
    void testAnimationStateMachineWithHumanoidRig() {
        AnimationStateMachine fsm = new AnimationStateMachine();
        fsm.registerAnimation(AnimationState.IDLE, StandardAnimations.createIdleAnimation());
        fsm.registerAnimation(AnimationState.WALK, StandardAnimations.createWalkAnimation());
        fsm.registerAnimation(AnimationState.RUN, StandardAnimations.createRunAnimation());

        assertEquals(AnimationState.IDLE, fsm.getCurrentState());

        // Update with horizontal speed 2.0 m/s -> should transition to WALK
        fsm.update(0.1f, 1 /* forward */, 2.0f);
        assertEquals(AnimationState.WALK, fsm.getTargetState());

        // Advance blend to complete transition
        fsm.update(0.2f, 1, 2.0f);
        assertEquals(AnimationState.WALK, fsm.getCurrentState());

        // Advance to RUN (speed 5.0 m/s)
        fsm.update(0.1f, 1, 5.0f);
        assertEquals(AnimationState.RUN, fsm.getTargetState());

        // Evaluate blended pose with skeleton
        Skeleton skeleton = HumanoidRig.createDefaultSkeleton();
        var blendedPose = fsm.evaluate(skeleton);
        assertNotNull(blendedPose);
        assertEquals(HumanoidRig.BONE_COUNT, blendedPose.jointPoseMatrices().size());
    }
}
