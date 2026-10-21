package com.ffxi.model;

import java.util.List;

public record SkeletonMeshClothProperties(
        int lockedSingleJointVertices,
        int lockedDoubleJointVertices,
        int clothLinkDataOffset,
        int clothLinkDataSize,
        int adjacentLinksSize,
        int diagonalLinksSize,
        float adjacentSpringFactor,
        float diagonalSpringFactor,
        float exponentialSpringFactor,
        float gravityFactor,
        float movementFactor,
        List<SkeletonMeshClothLink> adjacentLinks,
        List<SkeletonMeshClothLink> diagonalLinks
) {
    public SkeletonMeshClothProperties {
        adjacentLinks = List.copyOf(adjacentLinks != null ? adjacentLinks : List.of());
        diagonalLinks = List.copyOf(diagonalLinks != null ? diagonalLinks : List.of());
    }
}
