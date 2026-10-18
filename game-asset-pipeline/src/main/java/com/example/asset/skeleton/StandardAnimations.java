package com.example.asset.skeleton;

import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.asset.skeleton.HumanoidRig.*;

/**
 * Standard humanoid locomotion and posture keyframed skeletal animations:
 * IDLE, WALK, RUN, and REST.
 */
public final class StandardAnimations {

    private StandardAnimations() {
    }

    /**
     * Builds standard idle breathing loop (30 frames, 1.0s).
     */
    public static SkeletonAnimation createIdleAnimation() {
        int frames = 30;
        float frameDuration = 1.0f / 30.0f;
        Map<Integer, List<KeyFrameTransform>> tracks = new HashMap<>();

        for (int b = 0; b < BONE_COUNT; b++) {
            tracks.put(b, new ArrayList<>(frames));
        }

        for (int f = 0; f < frames; f++) {
            float phase = (float) (2.0 * Math.PI * f / frames);
            float breath = (float) Math.sin(phase);

            // Pelvis
            tracks.get(BONE_ROOT).add(new KeyFrameTransform(
                    Quaternion.IDENTITY,
                    new Vector3f(0f, 0.95f + breath * 0.005f, 0f),
                    Vector3f.ONE
            ));

            // Spine & Chest
            Quaternion spineRot = Quaternion.fromEuler(breath * 0.02f, 0f, 0f);
            tracks.get(BONE_SPINE).add(new KeyFrameTransform(spineRot, new Vector3f(0f, 0.22f, 0f), Vector3f.ONE));
            tracks.get(BONE_CHEST).add(new KeyFrameTransform(spineRot, new Vector3f(0f, 0.24f, 0f), Vector3f.ONE));

            // Neck & Head
            Quaternion headRot = Quaternion.fromEuler(-breath * 0.015f, (float) Math.sin(phase * 0.5) * 0.03f, 0f);
            tracks.get(BONE_NECK).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, 0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_HEAD).add(new KeyFrameTransform(headRot, new Vector3f(0f, 0.16f, 0f), Vector3f.ONE));

            // Arms slightly relaxed at sides
            Quaternion lArmRot = Quaternion.fromEuler(0.05f + breath * 0.02f, 0f, 0.08f);
            Quaternion rArmRot = Quaternion.fromEuler(0.05f + breath * 0.02f, 0f, -0.08f);
            tracks.get(BONE_L_SHOULDER).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.18f, 0.05f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_ARM).add(new KeyFrameTransform(lArmRot, new Vector3f(-0.16f, -0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_FOREARM).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.02f, -0.26f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_HAND).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.01f, -0.18f, 0f), Vector3f.ONE));

            tracks.get(BONE_R_SHOULDER).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.18f, 0.05f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_ARM).add(new KeyFrameTransform(rArmRot, new Vector3f(0.16f, -0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_FOREARM).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.02f, -0.26f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_HAND).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.01f, -0.18f, 0f), Vector3f.ONE));

            // Legs
            tracks.get(BONE_L_HIP).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.12f, -0.06f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_THIGH).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.02f, -0.38f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_CALF).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, -0.40f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_FOOT).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, -0.10f, 0.12f), Vector3f.ONE));

            tracks.get(BONE_R_HIP).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.12f, -0.06f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_THIGH).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.02f, -0.38f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_CALF).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, -0.40f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_FOOT).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, -0.10f, 0.12f), Vector3f.ONE));
        }

        return new SkeletonAnimation("anim_idle", BONE_COUNT, frames, frameDuration, tracks);
    }

    /**
     * Builds standard rhythmic walking cycle (30 frames, 1.0s).
     */
    public static SkeletonAnimation createWalkAnimation() {
        int frames = 30;
        float frameDuration = 1.0f / 30.0f;
        Map<Integer, List<KeyFrameTransform>> tracks = new HashMap<>();

        for (int b = 0; b < BONE_COUNT; b++) {
            tracks.put(b, new ArrayList<>(frames));
        }

        for (int f = 0; f < frames; f++) {
            float phase = (float) (2.0 * Math.PI * f / frames);
            float strideL = (float) Math.sin(phase);
            float strideR = -strideL;
            float bounce = -(float) Math.abs(Math.sin(phase * 2.0)) * 0.03f;

            // Pelvis vertical bounce and pelvic yaw sway
            Quaternion pelvisRot = Quaternion.fromEuler(0.04f, -strideL * 0.06f, 0f);
            tracks.get(BONE_ROOT).add(new KeyFrameTransform(
                    pelvisRot,
                    new Vector3f(0f, 0.95f + bounce, 0f),
                    Vector3f.ONE
            ));

            // Spine counter-rotation
            Quaternion spineRot = Quaternion.fromEuler(-0.02f, strideL * 0.05f, 0f);
            tracks.get(BONE_SPINE).add(new KeyFrameTransform(spineRot, new Vector3f(0f, 0.22f, 0f), Vector3f.ONE));
            tracks.get(BONE_CHEST).add(new KeyFrameTransform(spineRot, new Vector3f(0f, 0.24f, 0f), Vector3f.ONE));
            tracks.get(BONE_NECK).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, 0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_HEAD).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, 0.16f, 0f), Vector3f.ONE));

            // Opposite arm swing
            float lArmPitch = -strideL * 0.35f;
            float rArmPitch = -strideR * 0.35f;
            tracks.get(BONE_L_SHOULDER).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.18f, 0.05f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_ARM).add(new KeyFrameTransform(Quaternion.fromEuler(lArmPitch, 0f, 0.05f), new Vector3f(-0.16f, -0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_FOREARM).add(new KeyFrameTransform(Quaternion.fromEuler(Math.max(0f, lArmPitch * 0.5f), 0f, 0f), new Vector3f(-0.02f, -0.26f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_HAND).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.01f, -0.18f, 0f), Vector3f.ONE));

            tracks.get(BONE_R_SHOULDER).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.18f, 0.05f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_ARM).add(new KeyFrameTransform(Quaternion.fromEuler(rArmPitch, 0f, -0.05f), new Vector3f(0.16f, -0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_FOREARM).add(new KeyFrameTransform(Quaternion.fromEuler(Math.max(0f, rArmPitch * 0.5f), 0f, 0f), new Vector3f(0.02f, -0.26f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_HAND).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.01f, -0.18f, 0f), Vector3f.ONE));

            // Leg strides: Left leg
            float lThighAngle = strideL * 0.45f;
            float lCalfAngle = strideL < 0 ? -strideL * 0.6f : 0f;
            tracks.get(BONE_L_HIP).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.12f, -0.06f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_THIGH).add(new KeyFrameTransform(Quaternion.fromEuler(lThighAngle, 0f, 0f), new Vector3f(-0.02f, -0.38f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_CALF).add(new KeyFrameTransform(Quaternion.fromEuler(lCalfAngle, 0f, 0f), new Vector3f(0f, -0.40f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_FOOT).add(new KeyFrameTransform(Quaternion.fromEuler(-lThighAngle * 0.5f, 0f, 0f), new Vector3f(0f, -0.10f, 0.12f), Vector3f.ONE));

            // Right leg
            float rThighAngle = strideR * 0.45f;
            float rCalfAngle = strideR < 0 ? -strideR * 0.6f : 0f;
            tracks.get(BONE_R_HIP).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.12f, -0.06f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_THIGH).add(new KeyFrameTransform(Quaternion.fromEuler(rThighAngle, 0f, 0f), new Vector3f(0.02f, -0.38f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_CALF).add(new KeyFrameTransform(Quaternion.fromEuler(rCalfAngle, 0f, 0f), new Vector3f(0f, -0.40f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_FOOT).add(new KeyFrameTransform(Quaternion.fromEuler(-rThighAngle * 0.5f, 0f, 0f), new Vector3f(0f, -0.10f, 0.12f), Vector3f.ONE));
        }

        return new SkeletonAnimation("anim_walk", BONE_COUNT, frames, frameDuration, tracks);
    }

    /**
     * Builds dynamic athletic run cycle (20 frames, ~0.66s).
     */
    public static SkeletonAnimation createRunAnimation() {
        int frames = 20;
        float frameDuration = 0.66f / 20.0f;
        Map<Integer, List<KeyFrameTransform>> tracks = new HashMap<>();

        for (int b = 0; b < BONE_COUNT; b++) {
            tracks.put(b, new ArrayList<>(frames));
        }

        for (int f = 0; f < frames; f++) {
            float phase = (float) (2.0 * Math.PI * f / frames);
            float strideL = (float) Math.sin(phase);
            float strideR = -strideL;
            float bounce = -(float) Math.abs(Math.sin(phase * 2.0)) * 0.06f;

            // Forward torso lean
            Quaternion rootRot = Quaternion.fromEuler(0.18f, -strideL * 0.10f, 0f);
            tracks.get(BONE_ROOT).add(new KeyFrameTransform(
                    rootRot,
                    new Vector3f(0f, 0.92f + bounce, 0f),
                    Vector3f.ONE
            ));

            tracks.get(BONE_SPINE).add(new KeyFrameTransform(Quaternion.fromEuler(0.05f, strideL * 0.08f, 0f), new Vector3f(0f, 0.22f, 0f), Vector3f.ONE));
            tracks.get(BONE_CHEST).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, 0.24f, 0f), Vector3f.ONE));
            tracks.get(BONE_NECK).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, 0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_HEAD).add(new KeyFrameTransform(Quaternion.fromEuler(-0.15f, 0f, 0f), new Vector3f(0f, 0.16f, 0f), Vector3f.ONE));

            // Bent arms pumping
            float lArmPitch = -strideL * 0.70f;
            float rArmPitch = -strideR * 0.70f;
            tracks.get(BONE_L_SHOULDER).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.18f, 0.05f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_ARM).add(new KeyFrameTransform(Quaternion.fromEuler(lArmPitch, 0f, 0.1f), new Vector3f(-0.16f, -0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_FOREARM).add(new KeyFrameTransform(Quaternion.fromEuler(0.9f, 0f, 0f), new Vector3f(-0.02f, -0.26f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_HAND).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.01f, -0.18f, 0f), Vector3f.ONE));

            tracks.get(BONE_R_SHOULDER).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.18f, 0.05f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_ARM).add(new KeyFrameTransform(Quaternion.fromEuler(rArmPitch, 0f, -0.1f), new Vector3f(0.16f, -0.12f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_FOREARM).add(new KeyFrameTransform(Quaternion.fromEuler(0.9f, 0f, 0f), new Vector3f(0.02f, -0.26f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_HAND).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.01f, -0.18f, 0f), Vector3f.ONE));

            // Leg strides (wider amplitude)
            float lThighAngle = strideL * 0.75f;
            float lCalfAngle = strideL < 0 ? -strideL * 1.1f : 0f;
            tracks.get(BONE_L_HIP).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(-0.12f, -0.06f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_THIGH).add(new KeyFrameTransform(Quaternion.fromEuler(lThighAngle, 0f, 0f), new Vector3f(-0.02f, -0.38f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_CALF).add(new KeyFrameTransform(Quaternion.fromEuler(lCalfAngle, 0f, 0f), new Vector3f(0f, -0.40f, 0f), Vector3f.ONE));
            tracks.get(BONE_L_FOOT).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, -0.10f, 0.12f), Vector3f.ONE));

            float rThighAngle = strideR * 0.75f;
            float rCalfAngle = strideR < 0 ? -strideR * 1.1f : 0f;
            tracks.get(BONE_R_HIP).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0.12f, -0.06f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_THIGH).add(new KeyFrameTransform(Quaternion.fromEuler(rThighAngle, 0f, 0f), new Vector3f(0.02f, -0.38f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_CALF).add(new KeyFrameTransform(Quaternion.fromEuler(rCalfAngle, 0f, 0f), new Vector3f(0f, -0.40f, 0f), Vector3f.ONE));
            tracks.get(BONE_R_FOOT).add(new KeyFrameTransform(Quaternion.IDENTITY, new Vector3f(0f, -0.10f, 0.12f), Vector3f.ONE));
        }

        return new SkeletonAnimation("anim_run", BONE_COUNT, frames, frameDuration, tracks);
    }

    /**
     * Builds and configures an AnimationStateMachine registered with standard IDLE, WALK, and RUN locomotion animations.
     */
    public static AnimationStateMachine createLocomotionStateMachine() {
        AnimationStateMachine asm = new AnimationStateMachine();
        asm.registerAnimation(AnimationState.IDLE, createIdleAnimation());
        asm.registerAnimation(AnimationState.WALK, createWalkAnimation());
        asm.registerAnimation(AnimationState.RUN, createRunAnimation());
        return asm;
    }
}
