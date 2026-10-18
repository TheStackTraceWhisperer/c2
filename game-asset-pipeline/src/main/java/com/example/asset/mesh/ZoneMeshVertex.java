package com.example.asset.mesh;

import com.example.asset.geometry.Vector3f;

/**
 * A vertex in a static visual zone mesh.
 */
public record ZoneMeshVertex(
        Vector3f position,
        Vector3f normal,
        float u,
        float v,
        int r,
        int g,
        int b,
        int a
) {}
