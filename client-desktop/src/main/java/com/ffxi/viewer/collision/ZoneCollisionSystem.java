package com.ffxi.viewer.collision;

import com.example.asset.collision.CollisionMap;
import com.example.asset.collision.CollisionTransform;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.Vector3f;

import java.util.List;

/**
 * Client-side zone collision facade backed by the unified {@link CollisionMap}.
 */
public final class ZoneCollisionSystem {

    private static final float WALL_NORMAL_Y_MAX = 0.3f;
    private static final int MAX_FIND_ANY_FLOOR_TESTS = 1000;

    private final CollisionMap collisionMap;

    public ZoneCollisionSystem(CollisionMap collisionMap) {
        this.collisionMap = collisionMap;
    }

    public CollisionMap collisionMap() {
        return collisionMap;
    }

    /**
     * Finds the highest floor Y in native FFXI world space (-Y = Up, +Y = Down) at (worldX, worldZ)
     * by casting a ray downward (+Y) from (worldX, startY, worldZ).
     */
    public float findFloorY(float worldX, float worldZ, float startY) {
        if (collisionMap == null || !Float.isFinite(worldX) || !Float.isFinite(worldZ) || !Float.isFinite(startY)) {
            return Float.NaN;
        }
        return collisionMap.raycastFloor(new Vector3f(worldX, startY, worldZ), 0.0f, Float.POSITIVE_INFINITY, true)
                .map(hit -> hit.point().y())
                .orElse(Float.NaN);
    }

    /**
     * Returns the floor {@link CollisionTransform} beneath {@code (worldX, worldY, worldZ)}
     * (containing retail floor point-light indices and sub-area environment ID), or {@code null}.
     */
    public CollisionTransform findFloorTransform(float worldX, float worldY, float worldZ) {
        if (collisionMap == null || !Float.isFinite(worldX) || !Float.isFinite(worldY) || !Float.isFinite(worldZ)) {
            return null;
        }
        return collisionMap.findFloorTransform(new Vector3f(worldX, worldY, worldZ), 0.5f, 4.0f)
                .orElse(null);
    }

    public float findSpawnFloorY() {
        float y = findFloorY(0.0f, 0.0f, -2000.0f);
        if (Float.isFinite(y)) {
            return y;
        }

        float[] offsets = {-50.0f, 50.0f, -100.0f, 100.0f, -200.0f, 200.0f};
        for (float ox : offsets) {
            for (float oz : offsets) {
                y = findFloorY(ox, oz, -2000.0f);
                if (Float.isFinite(y)) {
                    return y;
                }
            }
        }
        return Float.NaN;
    }

    /** Returns {worldX, worldY, worldZ} for any floor found near zone center, or null. */
    public float[] findAnyFloor() {
        if (collisionMap == null) {
            return null;
        }
        int nx = collisionMap.totalCellsX();
        int nz = collisionMap.totalCellsZ();
        int cx = nx / 2;
        int cz = nz / 2;
        int maxRadius = Math.max(nx, nz) / 2 + 1;
        int tested = 0;

        for (int radius = 0; radius <= maxRadius && tested < MAX_FIND_ANY_FLOOR_TESTS; radius++) {
            for (int dz = -radius; dz <= radius && tested < MAX_FIND_ANY_FLOOR_TESTS; dz++) {
                for (int dx = -radius; dx <= radius && tested < MAX_FIND_ANY_FLOOR_TESTS; dx++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    float[] ffxiXZ = collisionMap.getBlockCenterXZ(cx + dx, cz + dz);
                    if (ffxiXZ == null) {
                        continue;
                    }
                    tested++;
                    float worldX = ffxiXZ[0];
                    float worldZ = ffxiXZ[1];
                    float worldY = findFloorY(worldX, worldZ, -2000.0f);
                    if (Float.isFinite(worldY)) {
                        return new float[]{worldX, worldY, worldZ};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns all pre-transformed world-space collision triangles in this zone.
     */
    public List<CollisionTriangle> getAllWorldTriangles() {
        return collisionMap == null ? List.of() : collisionMap.allWorldSpaceTriangles();
    }

    /**
     * Casts a 3D segment from {@code (x0, y0, z0)} to {@code (x1, y1, z1)} and returns the distance
     * to the closest intersecting collision triangle {@code >= minT}, or {@link Float#NaN} if clear.
     */
    public float raycastSegmentDistance(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float minT
    ) {
        if (collisionMap == null) {
            return Float.NaN;
        }
        return collisionMap.raycastSegment(
                new Vector3f(x0, y0, z0),
                new Vector3f(x1, y1, z1),
                minT,
                null
        ).map(RayHit::distance).orElse(Float.NaN);
    }

    /**
     * Casts a 3D segment from {@code (x0, y0, z0)} to {@code (x1, y1, z1)} against wall/barrier triangles
     * ({@code TriFlags.isHitWall()} or {@code |ny| < WALL_NORMAL_Y_MAX}) and returns the closest hit
     * distance {@code >= minT}, or {@link Float#NaN} if clear.
     */
    public float raycastWallDistance(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float minT
    ) {
        if (collisionMap == null) {
            return Float.NaN;
        }
        return collisionMap.raycastSegment(
                new Vector3f(x0, y0, z0),
                new Vector3f(x1, y1, z1),
                minT,
                tri -> tri.flags().isHitWall() || Math.abs(tri.normal().y()) < WALL_NORMAL_Y_MAX
        ).map(RayHit::distance).orElse(Float.NaN);
    }
}
