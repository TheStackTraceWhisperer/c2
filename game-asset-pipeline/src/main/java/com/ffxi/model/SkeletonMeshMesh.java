package com.ffxi.model;

import java.util.List;

public record SkeletonMeshMesh(
        int opcode,
        String opcodeName,
        String textureName,
        int triangleCount,
        int vertexCount,
        boolean textured,
        boolean untextured,
        boolean singleColor,
        boolean mirrored,
        SkeletonMeshRenderProperties renderProperties,
        List<SkeletonMeshFaceVertex> vertices,
        List<SkeletonMeshVertex> resolvedVertices
) {
    public SkeletonMeshMesh {
        textureName = textureName != null ? textureName : "";
        vertices = List.copyOf(vertices != null ? vertices : List.of());
        resolvedVertices = List.copyOf(resolvedVertices != null ? resolvedVertices : List.of());
    }
}
