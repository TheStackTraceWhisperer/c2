package com.ffxi.model;

public record SkeletonMeshClothLink(
        SkeletonMeshClothLinkVertex vertexA,
        SkeletonMeshClothLinkVertex vertexB,
        float baseLinkLength
) {
}
