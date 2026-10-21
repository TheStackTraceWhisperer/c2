package com.ffxi.model;

public record SkeletonMeshRenderProperties(
        BgraColor tFactor,
        float f0,
        float f1,
        int flag0,
        int displayType,
        int flag2,
        int flag3,
        float ambientMultiplier,
        int unknown0,
        int unknown1,
        int unknown2,
        float f4,
        int unknown3,
        float specularHighlightPower,
        boolean specularHighlightEnabled
) {
}
