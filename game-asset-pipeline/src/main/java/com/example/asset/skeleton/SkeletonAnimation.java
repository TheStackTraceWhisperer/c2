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
     * Evaluates interpolated joint transform at a given floating point frame index.
     */
    public KeyFrameTransform evaluate(int jointIndex, float frameTime) {
        List<KeyFrameTransform> frames = jointTracks.get(jointIndex);
        if (frames == null || frames.isEmpty()) {
            return KeyFrameTransform.IDENTITY;
        }

        if (frames.size() == 1 || frameTime <= 0.0f) {
            return frames.getFirst();
        }

        if (frameTime >= frames.size() - 1) {
            return frames.getLast();
        }

        int lower = (int) Math.floor(frameTime);
        int upper = lower + 1;
        float delta = frameTime - lower;

        KeyFrameTransform kfLower = frames.get(lower);
        KeyFrameTransform kfUpper = frames.get(upper);

        return KeyFrameTransform.interpolate(kfLower, kfUpper, delta);
    }

    public float totalDurationSeconds() {
        return Math.max(1, numFrames - 1) * frameDuration;
    }
}
