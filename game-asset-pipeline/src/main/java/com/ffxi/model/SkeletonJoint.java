package com.ffxi.model;

public record SkeletonJoint(
        int parentIndex,
        int padByte,
        Quat4f rotation,
        Vec3f translation
) {
}
