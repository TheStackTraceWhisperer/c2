package com.example.asset.skeleton;

import com.example.asset.geometry.Matrix4f;
import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnimationStateMachineTest {

    @Test
    @DisplayName("Transitions from IDLE to RUN when movement flags indicate forward motion")
    void testStateTransitions() {
        AnimationStateMachine asm = new AnimationStateMachine();
        assertEquals(AnimationState.IDLE, asm.getCurrentState());
        assertFalse(asm.isTransitioning());

        // Forward movement with 6 m/s sprint speed
        asm.update(0.05f, 1, 6.0f); // 1 = FORWARD

        assertEquals(AnimationState.RUN, asm.getTargetState());
        assertTrue(asm.isTransitioning());
        assertTrue(asm.getBlendWeight() > 0.0f);

        // Advance past transition duration (150ms default)
        asm.update(0.12f, 1, 6.0f);

        assertEquals(AnimationState.RUN, asm.getCurrentState());
        assertEquals(AnimationState.RUN, asm.getTargetState());
        assertFalse(asm.isTransitioning());
        assertEquals(1.0f, asm.getBlendWeight(), 0.001f);
    }

    @Test
    @DisplayName("Resolves WALK vs RUN based on speed threshold")
    void testWalkVsRunResolution() {
        AnimationStateMachine asm = new AnimationStateMachine();

        assertEquals(AnimationState.WALK, asm.resolveDesiredState(1, 2.5f));
        assertEquals(AnimationState.RUN, asm.resolveDesiredState(1, 5.0f));
        assertEquals(AnimationState.JUMP, asm.resolveDesiredState(16, 0.0f));
        assertEquals(AnimationState.REST, asm.resolveDesiredState(32, 0.0f));
        assertEquals(AnimationState.COMBAT_IDLE, asm.resolveDesiredState(64, 0.0f));
        assertEquals(AnimationState.IDLE, asm.resolveDesiredState(0, 0.0f));
    }

    @Test
    @DisplayName("Evaluates forward kinematics pose matrices during state blend")
    void testEvaluateBlendedPose() {
        AnimationStateMachine asm = new AnimationStateMachine();

        Joint root = new Joint(0, -1, Quaternion.IDENTITY, new Vector3f(0, 1, 0));
        Joint spine = new Joint(1, 0, Quaternion.IDENTITY, new Vector3f(0, 1, 0));
        Skeleton skeleton = new Skeleton("SKEL", List.of(root, spine), List.of(), List.of());

        // Create simple keyframe animation for IDLE
        KeyFrameTransform kf0 = new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0, 1, 0), Vector3f.ONE);
        SkeletonAnimation idleAnim = new SkeletonAnimation(
                "IDLE", 2, 2, 0.05f,
                Map.of(0, List.of(kf0, kf0), 1, List.of(kf0, kf0))
        );
        asm.registerAnimation(AnimationState.IDLE, idleAnim);

        BlendedPose pose = asm.evaluate(skeleton);
        assertNotNull(pose);
        assertEquals(2, pose.jointPoseMatrices().size());

        // Spine bone should be at y = 2 (root at y=1 + spine at y=1)
        Matrix4f spineMatrix = pose.jointPoseMatrices().get(1);
        assertEquals(2.0f, spineMatrix.get(13), 0.001f, "Spine bone translation Y should be 2.0 in model space");
    }
}
