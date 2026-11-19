package com.example.asset.collision;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.Matrix3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An instanced collision object placed in world space.
 */
public final class CollisionObject {

    private final int fileOffset;
    private final CollisionMesh mesh;
    private final CollisionTransform transform;

    private volatile List<CollisionTriangle> worldSpaceTriangles;
    private volatile BoundingBox worldSpaceBoundingBox;

    public CollisionObject(int fileOffset, CollisionMesh mesh, CollisionTransform transform) {
        this.fileOffset = fileOffset;
        this.mesh = Objects.requireNonNull(mesh, "mesh must not be null");
        this.transform = Objects.requireNonNull(transform, "transform must not be null");
    }

    public int fileOffset() {
        return fileOffset;
    }

    public CollisionMesh mesh() {
        return mesh;
    }

    public CollisionTransform transform() {
        return transform;
    }

    public List<CollisionTriangle> worldSpaceTriangles() {
        List<CollisionTriangle> result = worldSpaceTriangles;
        if (result == null) {
            synchronized (this) {
                result = worldSpaceTriangles;
                if (result == null) {
                    Matrix3f invTranspose = transform.toCollisionSpace().truncateTo3x3().transpose();
                    List<CollisionTriangle> transformed = new ArrayList<>(mesh.triangles().size());

                    for (CollisionTriangle tri : mesh.triangles()) {
                        transformed.add(tri.transform(transform.toWorldSpace(), invTranspose));
                    }
                    result = Collections.unmodifiableList(transformed);
                    worldSpaceTriangles = result;
                }
            }
        }
        return result;
    }

    public BoundingBox worldSpaceBoundingBox() {
        BoundingBox result = worldSpaceBoundingBox;
        if (result == null) {
            synchronized (this) {
                result = worldSpaceBoundingBox;
                if (result == null) {
                    BoundingBox.Builder builder = BoundingBox.builder();
                    for (CollisionTriangle tri : worldSpaceTriangles()) {
                        builder.include(tri.v0());
                        builder.include(tri.v1());
                        builder.include(tri.v2());
                    }
                    result = builder.build();
                    worldSpaceBoundingBox = result;
                }
            }
        }
        return result;
    }
}
