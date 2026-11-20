package com.example.asset.geometry;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RayTriangleColliderTest {

    private static final float EPSILON = 1e-4f;

    @Test
    void testDirectHit() {
        // Horizontal triangle at y = 5 with FFXI upward normal (0, -1, 0)
        Vector3f v0 = new Vector3f(0f, 5f, 0f);
        Vector3f v1 = new Vector3f(0f, 5f, 10f);
        Vector3f v2 = new Vector3f(10f, 5f, 0f);
        Vector3f normal = Vector3f.UP;

        CollisionTriangle tri = new CollisionTriangle(v0, v1, v2, normal, new TriFlags(0), TerrainType.STONE);

        // Ray cast down (+Y) from (2, 0, 2) above the y = 5 floor
        Ray ray = Ray.downwards(new Vector3f(2f, 0f, 2f));
        Optional<RayHit> hit = RayTriangleCollider.intersect(tri, ray);

        assertTrue(hit.isPresent());
        assertEquals(5.0f, hit.get().distance(), EPSILON);
        assertEquals(2.0f, hit.get().point().x(), EPSILON);
        assertEquals(5.0f, hit.get().point().y(), EPSILON);
        assertEquals(2.0f, hit.get().point().z(), EPSILON);
    }

    @Test
    void testMissOutside() {
        Vector3f v0 = new Vector3f(0f, 5f, 0f);
        Vector3f v1 = new Vector3f(0f, 5f, 10f);
        Vector3f v2 = new Vector3f(10f, 5f, 0f);
        Vector3f normal = Vector3f.UP;

        CollisionTriangle tri = new CollisionTriangle(v0, v1, v2, normal, new TriFlags(0), TerrainType.STONE);

        // Point outside the triangle (e.g. at 8, 0, 8)
        Ray ray = new Ray(new Vector3f(8f, 0f, 8f), Vector3f.DOWN);
        Optional<RayHit> hit = RayTriangleCollider.intersect(tri, ray);

        assertFalse(hit.isPresent());
    }

    @Test
    void testParallelRay() {
        Vector3f v0 = new Vector3f(0f, 5f, 0f);
        Vector3f v1 = new Vector3f(0f, 5f, 10f);
        Vector3f v2 = new Vector3f(10f, 5f, 0f);
        Vector3f normal = Vector3f.UP;

        CollisionTriangle tri = new CollisionTriangle(v0, v1, v2, normal, new TriFlags(0), TerrainType.STONE);

        // Ray parallel to y=5 plane
        Ray ray = new Ray(new Vector3f(2f, 0f, 2f), Vector3f.X);
        Optional<RayHit> hit = RayTriangleCollider.intersect(tri, ray);

        assertFalse(hit.isPresent());
    }

    @Test
    void testRayAwayDirection() {
        Vector3f v0 = new Vector3f(0f, 5f, 0f);
        Vector3f v1 = new Vector3f(0f, 5f, 10f);
        Vector3f v2 = new Vector3f(10f, 5f, 0f);
        Vector3f normal = Vector3f.UP;

        CollisionTriangle tri = new CollisionTriangle(v0, v1, v2, normal, new TriFlags(0), TerrainType.STONE);

        // Ray pointing UP (-Y) from (2, 0, 2), away from triangle at y=5
        Ray ray = new Ray(new Vector3f(2f, 0f, 2f), Vector3f.UP);
        Optional<RayHit> hit = RayTriangleCollider.intersect(tri, ray);

        assertFalse(hit.isPresent());

        // But bidirectional should hit with negative distance
        Optional<RayHit> bidiHit = RayTriangleCollider.intersect(tri, ray.origin(), ray.direction(), true, null);
        assertTrue(bidiHit.isPresent());
        assertEquals(-5.0f, bidiHit.get().distance(), EPSILON);
    }

    @Test
    void testMaxDistance() {
        Vector3f v0 = new Vector3f(0f, 5f, 0f);
        Vector3f v1 = new Vector3f(0f, 5f, 10f);
        Vector3f v2 = new Vector3f(10f, 5f, 0f);
        Vector3f normal = Vector3f.UP;

        CollisionTriangle tri = new CollisionTriangle(v0, v1, v2, normal, new TriFlags(0), TerrainType.STONE);

        // Max distance 3f is shorter than actual distance 5f
        Optional<RayHit> hit = RayTriangleCollider.intersect(tri, new Vector3f(2f, 0f, 2f), Vector3f.DOWN, false, 3.0f);
        assertFalse(hit.isPresent());
    }
}
