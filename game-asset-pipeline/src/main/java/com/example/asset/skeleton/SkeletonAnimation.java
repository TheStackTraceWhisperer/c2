package com.example.asset.skeleton;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skeletal keyframe animation track container providing LERP/SLERP interpolation across frames.
 */
public record SkeletonAnimation(
        String animationId,
        int numJoints,
        int numFrames,
        float frameDuration,
        Map<Integer, List<KeyFrameTransform>> jointTracks
) {
    public SkeletonAnimation {
        Map<Integer, List<KeyFrameTransform>> unmodifiableTracks = new HashMap<>();
        for (Map.Entry<Integer, List<KeyFrameTransform>> entry : jointTracks.entrySet()) {
            unmodifiableTracks.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        jointTracks = Collections.unmodifiableMap(unmodifiableTracks);
    }

    /**
     * Evaluates interpolated joint transform at a given floating point frame index (looping by default).
     */
    public KeyFrameTransform evaluate(int jointIndex, float frameTime) {
        return evaluate(jointIndex, frameTime, true);
    }

    /**
     * Evaluates interpolated joint transform at a given floating point frame index with optional looping.
     */
    public KeyFrameTransform evaluate(int jointIndex, float frameTime, boolean loop) {
        List<KeyFrameTransform> frames = jointTracks.get(jointIndex);
        if (frames == null || frames.isEmpty()) {
            return KeyFrameTransform.IDENTITY;
        }

        if (frames.size() == 1) {
            return frames.getFirst();
        }

        if (loop) {
            int count = frames.size();
            float f = frameTime % count;
            if (f < 0.0f) {
                f += count;
            }
            int lower = (int) Math.floor(f);
            int upper = (lower + 1) % count;
            float delta = f - lower;
            return KeyFrameTransform.interpolate(frames.get(lower), frames.get(upper), delta);
        } else {
            if (frameTime <= 0.0f) {
                return frames.getFirst();
            }
            if (frameTime >= frames.size() - 1) {
                return frames.getLast();
            }
            int lower = (int) Math.floor(frameTime);
            int upper = lower + 1;
            float delta = frameTime - lower;
            return KeyFrameTransform.interpolate(frames.get(lower), frames.get(upper), delta);
        }
    }

    /**
     * Evaluates interpolated joint transform at continuous time in seconds (looping by default).
     */
    public KeyFrameTransform evaluateAtTime(int jointIndex, float timeInSeconds) {
        return evaluateAtTime(jointIndex, timeInSeconds, true);
    }

    /**
     * Evaluates interpolated joint transform at continuous time in seconds with optional looping.
     */
    public KeyFrameTransform evaluateAtTime(int jointIndex, float timeInSeconds, boolean loop) {
        if (frameDuration <= 0.0f) {
            return evaluate(jointIndex, 0.0f, loop);
        }
        float frameTime = timeInSeconds / frameDuration;
        return evaluate(jointIndex, frameTime, loop);
    }

    public float totalDurationSeconds() {
        return numFrames * frameDuration;
    }
}
