package com.example.asset.skeleton;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard 21-bone humanoid skeletal rig for player races and town NPCs in Vana'diel.
 */
public final class HumanoidRig {

    public static final int BONE_ROOT = 0;
    public static final int BONE_SPINE = 1;
    public static final int BONE_CHEST = 2;
    public static final int BONE_NECK = 3;
    public static final int BONE_HEAD = 4;
    public static final int BONE_L_SHOULDER = 5;
    public static final int BONE_L_ARM = 6;
    public static final int BONE_L_FOREARM = 7;
    public static final int BONE_L_HAND = 8;
    public static final int BONE_R_SHOULDER = 9;
    public static final int BONE_R_ARM = 10;
    public static final int BONE_R_FOREARM = 11;
    public static final int BONE_R_HAND = 12;
    public static final int BONE_L_HIP = 13;
    public static final int BONE_L_THIGH = 14;
    public static final int BONE_L_CALF = 15;
    public static final int BONE_L_FOOT = 16;
    public static final int BONE_R_HIP = 17;
    public static final int BONE_R_THIGH = 18;
    public static final int BONE_R_CALF = 19;
    public static final int BONE_R_FOOT = 20;

    public static final int BONE_COUNT = 21;

    private HumanoidRig() {
    }

    /**
     * Builds standard humanoid skeleton with authentic bind pose dimensions (scale ~1.75m tall).
     */
    public static Skeleton createDefaultSkeleton() {
        List<Joint> joints = new ArrayList<>(BONE_COUNT);

        // Pelvis / Root
        joints.add(new Joint(BONE_ROOT, -1, Quaternion.IDENTITY, new Vector3f(0f, 0.95f, 0f)));

        // Torso & Head
        joints.add(new Joint(BONE_SPINE, BONE_ROOT, Quaternion.IDENTITY, new Vector3f(0f, 0.22f, 0f)));
        joints.add(new Joint(BONE_CHEST, BONE_SPINE, Quaternion.IDENTITY, new Vector3f(0f, 0.24f, 0f)));
        joints.add(new Joint(BONE_NECK, BONE_CHEST, Quaternion.IDENTITY, new Vector3f(0f, 0.12f, 0f)));
        joints.add(new Joint(BONE_HEAD, BONE_NECK, Quaternion.IDENTITY, new Vector3f(0f, 0.16f, 0f)));

        // Left Arm
        joints.add(new Joint(BONE_L_SHOULDER, BONE_CHEST, Quaternion.IDENTITY, new Vector3f(-0.18f, 0.05f, 0f)));
        joints.add(new Joint(BONE_L_ARM, BONE_L_SHOULDER, Quaternion.IDENTITY, new Vector3f(-0.16f, -0.12f, 0f)));
        joints.add(new Joint(BONE_L_FOREARM, BONE_L_ARM, Quaternion.IDENTITY, new Vector3f(-0.02f, -0.26f, 0f)));
        joints.add(new Joint(BONE_L_HAND, BONE_L_FOREARM, Quaternion.IDENTITY, new Vector3f(-0.01f, -0.18f, 0f)));

        // Right Arm
        joints.add(new Joint(BONE_R_SHOULDER, BONE_CHEST, Quaternion.IDENTITY, new Vector3f(0.18f, 0.05f, 0f)));
        joints.add(new Joint(BONE_R_ARM, BONE_R_SHOULDER, Quaternion.IDENTITY, new Vector3f(0.16f, -0.12f, 0f)));
        joints.add(new Joint(BONE_R_FOREARM, BONE_R_ARM, Quaternion.IDENTITY, new Vector3f(0.02f, -0.26f, 0f)));
        joints.add(new Joint(BONE_R_HAND, BONE_R_FOREARM, Quaternion.IDENTITY, new Vector3f(0.01f, -0.18f, 0f)));

        // Left Leg
        joints.add(new Joint(BONE_L_HIP, BONE_ROOT, Quaternion.IDENTITY, new Vector3f(-0.12f, -0.06f, 0f)));
        joints.add(new Joint(BONE_L_THIGH, BONE_L_HIP, Quaternion.IDENTITY, new Vector3f(-0.02f, -0.38f, 0f)));
        joints.add(new Joint(BONE_L_CALF, BONE_L_THIGH, Quaternion.IDENTITY, new Vector3f(0f, -0.40f, 0f)));
        joints.add(new Joint(BONE_L_FOOT, BONE_L_CALF, Quaternion.IDENTITY, new Vector3f(0f, -0.10f, 0.12f)));

        // Right Leg
        joints.add(new Joint(BONE_R_HIP, BONE_ROOT, Quaternion.IDENTITY, new Vector3f(0.12f, -0.06f, 0f)));
        joints.add(new Joint(BONE_R_THIGH, BONE_R_HIP, Quaternion.IDENTITY, new Vector3f(0.02f, -0.38f, 0f)));
        joints.add(new Joint(BONE_R_CALF, BONE_R_THIGH, Quaternion.IDENTITY, new Vector3f(0f, -0.40f, 0f)));
        joints.add(new Joint(BONE_R_FOOT, BONE_R_CALF, Quaternion.IDENTITY, new Vector3f(0f, -0.10f, 0.12f)));

        BoundingBox box = new BoundingBox(
                new Vector3f(-0.5f, 0.0f, -0.5f),
                new Vector3f(0.5f, 1.85f, 0.5f)
        );

        return new Skeleton("humanoid_21", joints, List.of(), List.of(box));
    }
}
