package com.example.asset.skeleton;

import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;

/**
 * Evaluated or keyframed transform for a joint at a specific point in time.
 */
public record KeyFrameTransform(
        Quaternion rotation,
        Vector3f translation,
        Vector3f scale
) {
    public static final KeyFrameTransform IDENTITY = new KeyFrameTransform(
            Quaternion.IDENTITY,
            new Vector3f(0f, 0f, 0f),
            new Vector3f(1f, 1f, 1f)
    );

    public static KeyFrameTransform interpolate(KeyFrameTransform a, KeyFrameTransform b, float t) {
        return new KeyFrameTransform(
                Quaternion.slerp(a.rotation(), b.rotation(), t),
                Vector3f.lerp(a.translation(), b.translation(), t),
                Vector3f.lerp(a.scale(), b.scale(), t)
        );
    }
}
