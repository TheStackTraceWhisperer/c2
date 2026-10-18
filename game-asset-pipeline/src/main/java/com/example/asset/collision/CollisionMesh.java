package com.example.asset.collision;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.CollisionTriangle;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An uninstanced collision mesh in local model space.
 */
public record CollisionMesh(
        int fileOffset,
        List<CollisionTriangle> triangles,
        BoundingBox boundingBox
) {

    public CollisionMesh {
        Objects.requireNonNull(triangles, "triangles must not be null");
        Objects.requireNonNull(boundingBox, "boundingBox must not be null");
        triangles = Collections.unmodifiableList(triangles);
    }
}
