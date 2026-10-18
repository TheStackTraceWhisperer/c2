package com.example.asset.skeleton;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 64-bone skeletal rig hierarchy and bind pose definitions for characters and monsters.
 */
public record Skeleton(
        String sectionId,
        List<Joint> joints,
        List<JointReference> references,
        List<BoundingBox> boundingBoxes
) {
    public Skeleton {
        joints = Collections.unmodifiableList(joints);
        references = Collections.unmodifiableList(references);
        boundingBoxes = Collections.unmodifiableList(boundingBoxes);
    }

    public int boneCount() {
        return joints.size();
    }

    /**
     * Computes the model-space bind pose matrices for all joints by hierarchical forward kinematics.
     */
    public List<Matrix4f> computeBindPoseMatrices() {
        List<Matrix4f> modelMatrices = new ArrayList<>(joints.size());
        for (int i = 0; i < joints.size(); i++) {
            Joint joint = joints.get(i);
            Matrix4f local = joint.toLocalTransform();
            if (joint.parentIndex() >= 0 && joint.parentIndex() < i) {
                Matrix4f parentMatrix = modelMatrices.get(joint.parentIndex());
                modelMatrices.add(parentMatrix.multiply(local));
            } else {
                modelMatrices.add(local);
            }
        }
        return modelMatrices;
    }
}
