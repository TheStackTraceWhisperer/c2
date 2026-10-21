package com.ffxi.model;

public record SkeletonMeshVertex(
        int sourceIndex,
        SkeletonMeshJointRef jointRef0,
        Integer jointIndex0,
        SkeletonMeshJointRef jointRef1,
        Integer jointIndex1,
        Vec3f p0,
        Vec3f p1,
        Vec3f n0,
        Vec3f n1,
        float joint0Weight,
        float joint1Weight
) {
}
