package com.example.asset.geometry;

import java.util.Objects;

/**
 * 3D collision triangle with surface normal, barrier flags, and terrain classification.
 */
public record CollisionTriangle(
        Vector3f v0,
        Vector3f v1,
        Vector3f v2,
        Vector3f normal,
        TriFlags flags,
        TerrainType terrainType
) {

    public CollisionTriangle {
        Objects.requireNonNull(v0, "v0 must not be null");
        Objects.requireNonNull(v1, "v1 must not be null");
        Objects.requireNonNull(v2, "v2 must not be null");
        Objects.requireNonNull(normal, "normal must not be null");
        Objects.requireNonNull(flags, "flags must not be null");
        Objects.requireNonNull(terrainType, "terrainType must not be null");
    }

    public float height() {
        float maxY = Math.max(v0.y(), Math.max(v1.y(), v2.y()));
        float minY = Math.min(v0.y(), Math.min(v1.y(), v2.y()));
        return maxY - minY;
    }

    public CollisionTriangle transform(Matrix4f toWorldSpace, Matrix3f invTransposeTransform) {
        Vector3f w0 = toWorldSpace.transform(v0);
        Vector3f w1 = toWorldSpace.transform(v1);
        Vector3f w2 = toWorldSpace.transform(v2);

        Vector3f wNormal = invTransposeTransform.transform(normal).normalize();

        // Ensure winding consistency with wNormal
        Vector3f crossNormal = w1.sub(w0).cross(w2.sub(w0)).normalize();
        float windingCheck = crossNormal.dot(wNormal);

        Vector3f newV0 = w0;
        Vector3f newV1 = (windingCheck < 0f) ? w2 : w1;
        Vector3f newV2 = (windingCheck < 0f) ? w1 : w2;

        return new CollisionTriangle(newV0, newV1, newV2, wNormal, flags, terrainType);
    }
}
