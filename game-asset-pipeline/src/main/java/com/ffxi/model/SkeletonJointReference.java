package com.ffxi.model;

public record SkeletonJointReference(
        int index,
        Vec3f unknownVector,
        Vec3f positionOffset,
        int fileOffset
) {
}
