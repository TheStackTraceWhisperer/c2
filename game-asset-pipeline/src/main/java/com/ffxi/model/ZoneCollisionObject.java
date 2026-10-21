package com.ffxi.model;

import java.util.List;

/** A single collision mesh instance with its world-space transform (16 floats, column-major). */
public record ZoneCollisionObject(
        List<ZoneCollisionTriangle> triangles,
        float[] toWorldSpace
) {
    public ZoneCollisionObject {
        triangles = List.copyOf(triangles != null ? triangles : List.of());
        toWorldSpace = toWorldSpace != null ? toWorldSpace.clone() : new float[16];
    }
}
