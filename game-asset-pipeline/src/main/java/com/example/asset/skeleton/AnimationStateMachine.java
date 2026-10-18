package com.example.asset.skeleton;

import com.example.asset.geometry.Matrix4f;
import com.example.asset.geometry.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic animation state machine managing locomotion poses, action transitions,
 * normalized cross-fading, and continuous forward kinematics evaluation for character actors and NPCs.
 */
public class AnimationStateMachine {

    public static final float DEFAULT_TRANSITION_DURATION = 0.15f; // 150ms blend

    private final Map<AnimationState, SkeletonAnimation> animations = new EnumMap<>(AnimationState.class);
    private AnimationState currentState = AnimationState.IDLE;
    private AnimationState targetState = AnimationState.IDLE;

    private float blendWeight = 1.0f; // 1.0 means fully in targetState (or currentState when not transitioning)
    private float transitionDuration = DEFAULT_TRANSITION_DURATION;
    private float playbackTime = 0.0f;
    private float targetPlaybackTime = 0.0f;

    public void registerAnimation(AnimationState state, SkeletonAnimation animation) {
        animations.put(state, animation);
    }

    public SkeletonAnimation getAnimation(AnimationState state) {
        return animations.get(state);
    }

    public void setTransitionDuration(float duration) {
        this.transitionDuration = Math.max(0.01f, duration);
    }

    /**
     * Determines desired state based on movement flags and horizontal speed,
     * advancing cross-fading blend progress and playback time.
     *
     * @param dt Delta time in seconds since last frame
     * @param movementFlags Protocol movement flags (e.g. forward, strafe, jumping, resting)
     * @param horizontalSpeed Current entity horizontal speed in meters per second
     */
    public void update(float dt, int movementFlags, float horizontalSpeed) {
        AnimationState desired = resolveDesiredState(movementFlags, horizontalSpeed);

        if (desired != targetState) {
            // Trigger new transition
            currentState = (blendWeight >= 1.0f) ? targetState : currentState;
            targetState = desired;
            blendWeight = 0.0f;
            targetPlaybackTime = 0.0f;
        }

        if (blendWeight < 1.0f) {
            blendWeight = Math.min(1.0f, blendWeight + (dt / transitionDuration));
            if (blendWeight >= 1.0f) {
                currentState = targetState;
                playbackTime = targetPlaybackTime;
            }
        }

        // Playback speed scaling proportional to velocity
        float speedMultiplier = 1.0f;
        if (targetState == AnimationState.RUN) {
            speedMultiplier = Math.max(0.5f, Math.min(2.0f, horizontalSpeed / 5.0f));
        } else if (targetState == AnimationState.WALK) {
            speedMultiplier = Math.max(0.5f, Math.min(1.5f, horizontalSpeed / 2.5f));
        }

        playbackTime += dt * speedMultiplier;
        targetPlaybackTime += dt * speedMultiplier;
    }

    public AnimationState resolveDesiredState(int movementFlags, float horizontalSpeed) {
        // Flag bitmask constants (matching MovementFlags protocol)
        final int FLAG_FORWARD = 1;
        final int FLAG_BACKWARD = 2;
        final int FLAG_STRAFE_LEFT = 4;
        final int FLAG_STRAFE_RIGHT = 8;
        final int FLAG_JUMPING = 16;
        final int FLAG_RESTING = 32;
        final int FLAG_ENGAGED = 64;

        if ((movementFlags & FLAG_RESTING) != 0) {
            return AnimationState.REST;
        }
        if ((movementFlags & FLAG_JUMPING) != 0) {
            return AnimationState.JUMP;
        }
        if ((movementFlags & (FLAG_FORWARD | FLAG_BACKWARD | FLAG_STRAFE_LEFT | FLAG_STRAFE_RIGHT)) != 0 || horizontalSpeed > 0.2f) {
            return (horizontalSpeed >= 4.0f) ? AnimationState.RUN : AnimationState.WALK;
        }
        if ((movementFlags & FLAG_ENGAGED) != 0) {
            return AnimationState.COMBAT_IDLE;
        }
        return AnimationState.IDLE;
    }

    /**
     * Evaluates blended forward kinematics joint transform matrices for the given skeleton.
     */
    public BlendedPose evaluate(Skeleton skeleton) {
        if (skeleton == null || skeleton.joints().isEmpty()) {
            return new BlendedPose(currentState, targetState, blendWeight, playbackTime, List.of());
        }

        List<Joint> joints = skeleton.joints();
        List<Matrix4f> pose = new ArrayList<>(joints.size());

        SkeletonAnimation animCurrent = animations.get(currentState);
        SkeletonAnimation animTarget = animations.get(targetState);

        for (int i = 0; i < joints.size(); i++) {
            Joint joint = joints.get(i);
            Matrix4f local;

            if (animCurrent == null && animTarget == null) {
                local = joint.toLocalTransform();
            } else if (animCurrent != null && (animTarget == null || blendWeight >= 1.0f && currentState == targetState)) {
                KeyFrameTransform kf = animCurrent.evaluateAtTime(i, playbackTime, true);
                local = kfToMatrix(kf);
            } else if (animCurrent == null && animTarget != null) {
                KeyFrameTransform kf = animTarget.evaluateAtTime(i, targetPlaybackTime, true);
                local = kfToMatrix(kf);
            } else {
                // Dual-track cross-fade blend
                KeyFrameTransform kf0 = animCurrent.evaluateAtTime(i, playbackTime, true);
                KeyFrameTransform kf1 = animTarget.evaluateAtTime(i, targetPlaybackTime, true);
                KeyFrameTransform blended = KeyFrameTransform.interpolate(kf0, kf1, blendWeight);
                local = kfToMatrix(blended);
            }

            if (joint.parentIndex() >= 0 && joint.parentIndex() < i) {
                Matrix4f parentMatrix = pose.get(joint.parentIndex());
                pose.add(parentMatrix.multiply(local));
            } else {
                pose.add(local);
            }
        }

        return new BlendedPose(currentState, targetState, blendWeight, playbackTime, pose);
    }

    private Matrix4f kfToMatrix(KeyFrameTransform kf) {
        Matrix4f rot = kf.rotation().toMatrix4f();
        Vector3f t = kf.translation();
        Vector3f s = kf.scale();
        Matrix4f trans = Matrix4f.translation(t.x(), t.y(), t.z());
        Matrix4f scale = Matrix4f.scaling(s.x(), s.y(), s.z());
        return trans.multiply(rot).multiply(scale);
    }

    public AnimationState getCurrentState() {
        return currentState;
    }

    public AnimationState getTargetState() {
        return targetState;
    }

    public float getBlendWeight() {
        return blendWeight;
    }

    public float getPlaybackTime() {
        return playbackTime;
    }

    public boolean isTransitioning() {
        return blendWeight < 1.0f && currentState != targetState;
    }
}
