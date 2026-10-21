package com.ffxi.model;

public record SkeletonMeshFaceVertex(
        int vertexIndex,
        float u,
        float v,
        BgraColor color
) {
}
