package com.example.asset.skeleton;

import com.example.asset.geometry.Matrix4f;
import com.example.asset.texture.DecodedTexture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite character actor model assembling a shared skeletal rig, multiple equipment pieces,
 * and authentic game textures decoded from ROM DAT sections.
 */
public record CompositeCharacterModel(
        Skeleton skeleton,
        Map<Integer, SkinnedMesh> equipmentSlots,
        Map<String, DecodedTexture> textures
) {
    public static final int SLOT_HEAD = 0;
    public static final int SLOT_BODY = 1;
    public static final int SLOT_HANDS = 2;
    public static final int SLOT_LEGS = 3;
    public static final int SLOT_FEET = 4;
    public static final int SLOT_MAIN = 5;
    public static final int SLOT_SUB = 6;
    public static final int SLOT_RANGED = 7;

    public CompositeCharacterModel(Skeleton skeleton, Map<Integer, SkinnedMesh> equipmentSlots) {
        this(skeleton, equipmentSlots, Map.of());
    }

    public CompositeCharacterModel {
        equipmentSlots = Collections.unmodifiableMap(new HashMap<>(equipmentSlots));
        textures = Collections.unmodifiableMap(new HashMap<>(textures));
    }

    /**
     * Computes the hierarchical forward kinematics pose matrices for all bones given an animation and time.
     */
    public List<Matrix4f> computePoseMatrices(SkeletonAnimation animation, float frameTime) {
        if (skeleton == null) {
            return List.of();
        }

        List<Joint> joints = skeleton.joints();
        List<Matrix4f> pose = new ArrayList<>(joints.size());

        for (int i = 0; i < joints.size(); i++) {
            Joint joint = joints.get(i);
            Matrix4f local;

            if (animation != null) {
                KeyFrameTransform kf = animation.evaluate(i, frameTime);
                Matrix4f rot = kf.rotation().toMatrix4f();
                Matrix4f trans = Matrix4f.translation(kf.translation().x(), kf.translation().y(), kf.translation().z());
                Matrix4f scale = Matrix4f.scaling(kf.scale().x(), kf.scale().y(), kf.scale().z());
                local = trans.multiply(rot).multiply(scale);
            } else {
                local = joint.toLocalTransform();
            }

            if (joint.parentIndex() >= 0 && joint.parentIndex() < i) {
                Matrix4f parentMatrix = pose.get(joint.parentIndex());
                pose.add(parentMatrix.multiply(local));
            } else {
                pose.add(local);
            }
        }

        return pose;
    }
}
