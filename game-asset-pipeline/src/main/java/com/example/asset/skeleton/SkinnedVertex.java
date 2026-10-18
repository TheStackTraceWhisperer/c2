package com.example.asset.skeleton;

import com.example.asset.geometry.Vector3f;

/**
 * A vertex influenced by up to 2 skeletal bones with linear blend skinning weights.
 */
public record SkinnedVertex(
        Vector3f position,
        Vector3f normal,
        float u,
        float v,
        int jointIndex0,
        int jointIndex1,
        float jointWeight0,
        float jointWeight1,
        int r,
        int g,
        int b,
        int a
) {
}
