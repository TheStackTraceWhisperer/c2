package com.ffxi.model;

/** A collision triangle in collision-local space (before the toWorldSpace transform). */
public record ZoneCollisionTriangle(
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float nx, float ny, float nz
) {
}
