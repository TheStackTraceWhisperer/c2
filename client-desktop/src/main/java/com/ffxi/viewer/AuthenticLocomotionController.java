package com.ffxi.viewer;

import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonResource;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Authentic retail FFXI character locomotion and posture controller.
 * Drives skeletal pose evaluation using authentic retail motion DAT animations,
 * supporting dual-slot upper/lower body layering and smooth dual-state cross-fading.
 */
public final class AuthenticLocomotionController {

    public enum State {
        IDLE,
        WALK,
        RUN,
        REST,
        DEAD,
        COMBAT
    }

    public static final float DEFAULT_TRANSITION_DURATION = 0.15f; // 150ms authentic cross-fade

    private final SkeletonResource skeleton;
    private final Map<State, CharacterAnimator> animators = new EnumMap<>(State.class);
    private final Matrix4f[] tPose;

    private State currentState = State.IDLE;
    private State targetState = State.IDLE;

    private float blendWeight = 1.0f; // 1.0 = fully in targetState (or currentState when not transitioning)
    private float transitionDuration = DEFAULT_TRANSITION_DURATION;
    private double currentTime = 0.0;
    private double targetTime = 0.0;

    public AuthenticLocomotionController(
            SkeletonResource skeleton,
            List<SkeletonAnimationResource> slot0Animations,
            List<SkeletonAnimationResource> slot1Animations
    ) {
        this.skeleton = skeleton;
        if (skeleton != null) {
            CharacterAnimator baseAnimator = new CharacterAnimator(skeleton, null, null);
            this.tPose = baseAnimator.computeTPoseMatrices();
        } else {
            this.tPose = new Matrix4f[0];
        }

        if (skeleton != null) {
            registerStateAnimator(State.IDLE, skeleton, slot0Animations, slot1Animations, "idl", "idle");
            registerStateAnimator(State.WALK, skeleton, slot0Animations, slot1Animations, "wlk", "walk");
            registerStateAnimator(State.RUN, skeleton, slot0Animations, slot1Animations, "run");
            registerStateAnimator(State.REST, skeleton, slot0Animations, slot1Animations, "std", "sit", "rest");
            registerStateAnimator(State.DEAD, skeleton, slot0Animations, slot1Animations, "ded", "die", "death");
            registerStateAnimator(State.COMBAT, skeleton, slot0Animations, slot1Animations, "cm0", "cm1", "atk", "com");
        }

        // Fallbacks if specific animations are missing in the DAT
        CharacterAnimator idleAnim = animators.get(State.IDLE);
        if (idleAnim == null && slot0Animations != null && !slot0Animations.isEmpty()) {
            idleAnim = new CharacterAnimator(skeleton, slot0Animations.get(0), (slot1Animations != null && !slot1Animations.isEmpty()) ? slot1Animations.get(0) : null);
            animators.put(State.IDLE, idleAnim);
        }

        if (!animators.containsKey(State.WALK)) {
            animators.put(State.WALK, animators.getOrDefault(State.RUN, idleAnim));
        }
        if (!animators.containsKey(State.RUN)) {
            animators.put(State.RUN, animators.getOrDefault(State.WALK, idleAnim));
        }
        if (!animators.containsKey(State.REST)) {
            animators.put(State.REST, idleAnim);
        }
        if (!animators.containsKey(State.DEAD)) {
            animators.put(State.DEAD, idleAnim);
        }
        if (!animators.containsKey(State.COMBAT)) {
            animators.put(State.COMBAT, idleAnim);
        }
    }

    private void registerStateAnimator(
            State state,
            SkeletonResource skeleton,
            List<SkeletonAnimationResource> slot0,
            List<SkeletonAnimationResource> slot1,
            String... prefixes
    ) {
        SkeletonAnimationResource s0 = findAnim(slot0, prefixes);
        SkeletonAnimationResource s1 = findAnim(slot1, prefixes);
        if (s0 != null) {
            animators.put(state, new CharacterAnimator(skeleton, s0, s1));
        }
    }

    private static SkeletonAnimationResource findAnim(List<SkeletonAnimationResource> anims, String... prefixes) {
        if (anims == null) return null;
        for (String prefix : prefixes) {
            String p = prefix.toLowerCase();
            for (SkeletonAnimationResource a : anims) {
                String id = a.sectionId().trim().toLowerCase();
                if (id.startsWith(p)) {
                    return a;
                }
            }
        }
        return null;
    }

    public void setTransitionDuration(float duration) {
        this.transitionDuration = Math.max(0.01f, duration);
    }

    public State getCurrentState() {
        return currentState;
    }

    public State getTargetState() {
        return targetState;
    }

    public boolean isTransitioning() {
        return blendWeight < 1.0f && currentState != targetState;
    }

    public float getBlendWeight() {
        return blendWeight;
    }

    /**
     * Determines desired locomotion state based on movement flags and velocity,
     * advancing cross-fading blend progress and animation playback times.
     */
    public void update(float dt, int movementFlags, float horizontalSpeed) {
        State desired = resolveDesiredState(movementFlags, horizontalSpeed);

        if (desired != targetState) {
            if (blendWeight >= 1.0f) {
                currentState = targetState;
                currentTime = targetTime;
            }
            targetState = desired;
            blendWeight = 0.0f;
            targetTime = 0.0;
        }

        if (blendWeight < 1.0f) {
            blendWeight = Math.min(1.0f, blendWeight + (dt / transitionDuration));
            if (blendWeight >= 1.0f) {
                currentState = targetState;
                currentTime = targetTime;
            }
        }

        // Playback speed scaling proportional to velocity
        float speedMultiplier = 1.0f;
        if (targetState == State.RUN) {
            speedMultiplier = Math.max(0.6f, Math.min(1.8f, horizontalSpeed / 5.0f));
        } else if (targetState == State.WALK) {
            speedMultiplier = Math.max(0.6f, Math.min(1.5f, horizontalSpeed / 2.5f));
        }

        currentTime += dt * speedMultiplier;
        targetTime += dt * speedMultiplier;
    }

    public State resolveDesiredState(int movementFlags, float horizontalSpeed) {
        final int FLAG_FORWARD = 1;
        final int FLAG_BACKWARD = 2;
        final int FLAG_STRAFE_LEFT = 4;
        final int FLAG_STRAFE_RIGHT = 8;
        final int FLAG_RESTING = 32;
        final int FLAG_ENGAGED = 64;

        if ((movementFlags & FLAG_RESTING) != 0) {
            return State.REST;
        }
        if ((movementFlags & (FLAG_FORWARD | FLAG_BACKWARD | FLAG_STRAFE_LEFT | FLAG_STRAFE_RIGHT)) != 0 || horizontalSpeed > 0.2f) {
            return (horizontalSpeed >= 3.8f) ? State.RUN : State.WALK;
        }
        if ((movementFlags & FLAG_ENGAGED) != 0) {
            return State.COMBAT;
        }
        return State.IDLE;
    }

    /**
     * Computes the current world-space joint matrices for uploading to CharacterShader.
     * Evaluates smooth cross-fading when transitioning between states.
     */
    public Matrix4f[] computePoseMatrices() {
        CharacterAnimator curr = animators.get(currentState);
        CharacterAnimator target = animators.get(targetState);

        if (target == null && curr == null) {
            return tPose;
        }
        if (target == null) {
            return curr.computePoseMatrices(currentTime);
        }
        if (curr == null || blendWeight >= 1.0f || curr == target) {
            return target.computePoseMatrices(targetTime);
        }

        return curr.computeBlendedPoseMatrices(target, currentTime, targetTime, blendWeight);
    }
}
