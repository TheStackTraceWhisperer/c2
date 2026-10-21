package com.example.asset.geometry;

import java.util.Optional;

/**
 * High-performance ray-triangle intersection routines matching FFXI/Xim collision logic.
 */
public final class RayTriangleCollider {

    private static final float EPSILON = 1e-5f;
    private static final float EDGE_TOLERANCE = -1e-6f;

    private RayTriangleCollider() {
    }

    public static Optional<RayHit> intersect(CollisionTriangle tri, Ray ray) {
        return intersect(tri, ray.origin(), ray.direction(), false, null);
    }

    public static Optional<RayHit> intersect(
            CollisionTriangle tri,
            Vector3f origin,
            Vector3f direction,
            boolean bidirectional,
            Float maxDistance
    ) {
        Vector3f normal = tri.normal();
        float d = normal.dot(direction);
        if (Math.abs(d) <= EPSILON) {
            return Optional.empty(); // Parallel to triangle plane
        }

        float planeConstant = normal.dot(tri.v0());
        float rayDistance = (planeConstant - normal.dot(origin)) / d;

        if (!bidirectional && rayDistance < 0.0f) {
            return Optional.empty(); // Behind ray origin
        }
        if (maxDistance != null && rayDistance > maxDistance) {
            return Optional.empty(); // Beyond max distance
        }

        Vector3f q = origin.add(direction.mul(rayDistance));

        // Inside-outside test using edge cross products (works for both CCW and CW windings)
        float c0 = tri.v1().sub(tri.v0()).cross(q.sub(tri.v0())).dot(normal);
        float c1 = tri.v2().sub(tri.v1()).cross(q.sub(tri.v1())).dot(normal);
        float c2 = tri.v0().sub(tri.v2()).cross(q.sub(tri.v2())).dot(normal);

        boolean hasNeg = (c0 < EDGE_TOLERANCE) || (c1 < EDGE_TOLERANCE) || (c2 < EDGE_TOLERANCE);
        boolean hasPos = (c0 > -EDGE_TOLERANCE) || (c1 > -EDGE_TOLERANCE) || (c2 > -EDGE_TOLERANCE);

        if (hasNeg && hasPos) {
            return Optional.empty();
        }

        return Optional.of(new RayHit(q, rayDistance));
    }
}
