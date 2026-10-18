package com.example.asset.skeleton;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.Matrix4f;
import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkeletonTest {

    @Test
    @DisplayName("Hierarchical forward kinematics bind pose calculation")
    void testForwardKinematics() {
        // Build 3-bone limb: Root (bone 0) at origin, Child (bone 1) offset by +2 on Y, Grandchild (bone 2) offset by +1.5 on Y
        Joint root = new Joint(0, -1, Quaternion.IDENTITY, new Vector3f(0f, 0f, 0f));
        Joint child = new Joint(1, 0, Quaternion.IDENTITY, new Vector3f(0f, 2.0f, 0f));
        Joint grandchild = new Joint(2, 1, Quaternion.IDENTITY, new Vector3f(0f, 1.5f, 0f));

        Skeleton skeleton = new Skeleton(
                "test_skel",
                List.of(root, child, grandchild),
                List.of(),
                List.of(BoundingBox.EMPTY)
        );

        List<Matrix4f> bindPoses = skeleton.computeBindPoseMatrices();
        assertEquals(3, bindPoses.size());

        // Root position in model space: (0, 0, 0)
        Vector3f rootPos = bindPoses.get(0).transform(new Vector3f(0f, 0f, 0f));
        assertEquals(0f, rootPos.x(), 1e-5f);
        assertEquals(0f, rootPos.y(), 1e-5f);
        assertEquals(0f, rootPos.z(), 1e-5f);

        // Child position in model space: (0, 2, 0)
        Vector3f childPos = bindPoses.get(1).transform(new Vector3f(0f, 0f, 0f));
        assertEquals(0f, childPos.x(), 1e-5f);
        assertEquals(2.0f, childPos.y(), 1e-5f);
        assertEquals(0f, childPos.z(), 1e-5f);

        // Grandchild position in model space: (0, 2 + 1.5 = 3.5, 0)
        Vector3f grandchildPos = bindPoses.get(2).transform(new Vector3f(0f, 0f, 0f));
        assertEquals(0f, grandchildPos.x(), 1e-5f);
        assertEquals(3.5f, grandchildPos.y(), 1e-5f);
        assertEquals(0f, grandchildPos.z(), 1e-5f);
    }

    @Test
    @DisplayName("CompositeCharacterModel animated pose evaluation")
    void testAnimatedPoseEvaluation() {
        Joint root = new Joint(0, -1, Quaternion.IDENTITY, new Vector3f(0f, 0f, 0f));
        Joint arm = new Joint(1, 0, Quaternion.IDENTITY, new Vector3f(1.0f, 0f, 0f));
        Skeleton skeleton = new Skeleton("human", List.of(root, arm), List.of(), List.of());

        // Animation track: arm moves from x=1.0 at frame 0 to x=3.0 at frame 2
        Map<Integer, List<KeyFrameTransform>> tracks = new HashMap<>();
        tracks.put(0, List.of(KeyFrameTransform.IDENTITY, KeyFrameTransform.IDENTITY, KeyFrameTransform.IDENTITY));
        tracks.put(1, List.of(
                new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(1.0f, 0f, 0f), new Vector3f(1f, 1f, 1f)),
                new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(2.0f, 0f, 0f), new Vector3f(1f, 1f, 1f)),
                new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(3.0f, 0f, 0f), new Vector3f(1f, 1f, 1f))
        ));

        SkeletonAnimation anim = new SkeletonAnimation("wave", 2, 3, 0.033f, tracks);
        CompositeCharacterModel character = new CompositeCharacterModel(skeleton, Map.of());

        // At frame 1.0, arm should be at x = 2.0
        List<Matrix4f> pose = character.computePoseMatrices(anim, 1.0f);
        Vector3f armPos = pose.get(1).transform(new Vector3f(0f, 0f, 0f));
        assertEquals(2.0f, armPos.x(), 1e-5f);

        // At frame 0.5 (interpolated), arm should be at x = 1.5
        List<Matrix4f> poseInterp = character.computePoseMatrices(anim, 0.5f);
        Vector3f armPosInterp = poseInterp.get(1).transform(new Vector3f(0f, 0f, 0f));
        assertEquals(1.5f, armPosInterp.x(), 1e-5f);
    }
}
