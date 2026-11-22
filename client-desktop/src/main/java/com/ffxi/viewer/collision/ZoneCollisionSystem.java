package com.ffxi.viewer.collision;

import com.ffxi.model.ZoneCollisionMap;
import com.ffxi.model.ZoneCollisionObject;
import com.ffxi.model.ZoneCollisionObjectGroup;
import com.ffxi.model.ZoneCollisionTriangle;

import java.util.ArrayList;
import java.util.List;

public final class ZoneCollisionSystem {

    private static final float FLOOR_NORMAL_Y_MIN = 0.5f;
    private static final float WALL_NORMAL_Y_MAX = 0.5f;
    private static final float RAY_EPSILON = 1.0e-10f;
    private static final float SEGMENT_EPSILON = 1.0e-8f;
    private static final int MAX_FIND_ANY_FLOOR_TESTS = 1000;

    private final ZoneCollisionMap collisionMap;

    public ZoneCollisionSystem(ZoneCollisionMap collisionMap) {
        this.collisionMap = collisionMap;
    }

    /**
     * Finds the highest floor Y in native FFXI world space (-Y = Up, +Y = Down) at (worldX, worldZ)
     * by casting a ray downward (+Y) from (worldX, startY, worldZ).
     */
    public float findFloorY(float worldX, float worldZ, float startY) {
        if (collisionMap == null || !Float.isFinite(worldX) || !Float.isFinite(worldZ) || !Float.isFinite(startY)) {
            return Float.NaN;
        }

        List<ZoneCollisionObjectGroup> groups = collisionMap.getGroups(worldX, worldZ);

        float bestFloorY = Float.POSITIVE_INFINITY;
        boolean found = false;
        for (ZoneCollisionObjectGroup group : groups) {
            for (ZoneCollisionObject object : group.objects()) {
                float hit = raycastDownward(object, worldX, startY, worldZ);
                if (Float.isFinite(hit) && hit >= startY && hit < bestFloorY) {
                    bestFloorY = hit;
                    found = true;
                }
            }
        }
        return found ? bestFloorY : Float.NaN;
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
        int nx = collisionMap.getNumBlocksX();
        int nz = collisionMap.getNumBlocksZ();
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

    public record TransformedTriangle(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float nx, float ny, float nz
    ) {}

    /**
     * Extracts and transforms all collision triangles in this zone into world space
     * using the exact same vertex transform as raycast collision.
     */
    public List<TransformedTriangle> getAllWorldTriangles() {
        if (collisionMap == null) {
            return List.of();
        }
        List<TransformedTriangle> result = new ArrayList<>();
        for (ZoneCollisionObject obj : collisionMap.allObjects()) {
            float[] m = obj.toWorldSpace();
            for (ZoneCollisionTriangle tri : obj.triangles()) {
                float[] v0 = transformVertex(m, tri.x0(), tri.y0(), tri.z0());
                float[] v1 = transformVertex(m, tri.x1(), tri.y1(), tri.z1());
                float[] v2 = transformVertex(m, tri.x2(), tri.y2(), tri.z2());

                float e0x = v1[0] - v0[0];
                float e0y = v1[1] - v0[1];
                float e0z = v1[2] - v0[2];
                float e1x = v2[0] - v0[0];
                float e1y = v2[1] - v0[1];
                float e1z = v2[2] - v0[2];
                float nx = e0y * e1z - e0z * e1y;
                float ny = e0z * e1x - e0x * e1z;
                float nz = e0x * e1y - e0y * e1x;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-6f) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                }
                result.add(new TransformedTriangle(
                        v0[0], v0[1], v0[2],
                        v1[0], v1[1], v1[2],
                        v2[0], v2[1], v2[2],
                        nx, ny, nz
                ));
            }
        }
        return result;
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
        return raycastSegment(x0, y0, z0, x1, y1, z1, minT, false);
    }

    /**
     * Casts a 3D segment from {@code (x0, y0, z0)} to {@code (x1, y1, z1)} against steep wall triangles
     * ({@code |ny| < WALL_NORMAL_Y_MAX}) and returns the closest hit distance {@code >= minT}, or
     * {@link Float#NaN} if clear.
     */
    public float raycastWallDistance(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float minT
    ) {
        return raycastSegment(x0, y0, z0, x1, y1, z1, minT, true);
    }

    private float raycastSegment(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float minT,
            boolean wallsOnly
    ) {
        if (collisionMap == null
                || !Float.isFinite(x0) || !Float.isFinite(y0) || !Float.isFinite(z0)
                || !Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(z1)
                || !Float.isFinite(minT)) {
            return Float.NaN;
        }

        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5f) {
            return Float.NaN;
        }

        float invLen = 1.0f / len;
        float dirX = dx * invLen;
        float dirY = dy * invLen;
        float dirZ = dz * invLen;

        List<ZoneCollisionObject> objects = collisionMap.getObjectsAlongSegment(x0, z0, x1, z1);
        float bestT = Float.POSITIVE_INFINITY;
        boolean hit = false;

        for (ZoneCollisionObject obj : objects) {
            float[] matrix = obj.toWorldSpace();
            for (ZoneCollisionTriangle tri : obj.triangles()) {
                float[] v0 = transformVertex(matrix, tri.x0(), tri.y0(), tri.z0());
                float[] v1 = transformVertex(matrix, tri.x1(), tri.y1(), tri.z1());
                float[] v2 = transformVertex(matrix, tri.x2(), tri.y2(), tri.z2());

                float e1x = v1[0] - v0[0];
                float e1y = v1[1] - v0[1];
                float e1z = v1[2] - v0[2];
                float e2x = v2[0] - v0[0];
                float e2y = v2[1] - v0[1];
                float e2z = v2[2] - v0[2];

                if (wallsOnly) {
                    float nx = e1y * e2z - e1z * e2y;
                    float ny = e1z * e2x - e1x * e2z;
                    float nz = e1x * e2y - e1y * e2x;
                    float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (normalLength < RAY_EPSILON || Math.abs(ny / normalLength) >= WALL_NORMAL_Y_MAX) {
                        continue;
                    }
                }

                // Double-sided Möller-Trumbore ray-triangle intersection
                float hx = dirY * e2z - dirZ * e2y;
                float hy = dirZ * e2x - dirX * e2z;
                float hz = dirX * e2y - dirY * e2x;
                float a = e1x * hx + e1y * hy + e1z * hz;
                if (Math.abs(a) < SEGMENT_EPSILON) {
                    continue;
                }

                float f = 1.0f / a;
                float sx = x0 - v0[0];
                float sy = y0 - v0[1];
                float sz = z0 - v0[2];
                float u = f * (sx * hx + sy * hy + sz * hz);
                if (u < 0.0f || u > 1.0f) {
                    continue;
                }

                float qx = sy * e1z - sz * e1y;
                float qy = sz * e1x - sx * e1z;
                float qz = sx * e1y - sy * e1x;
                float v = f * (dirX * qx + dirY * qy + dirZ * qz);
                if (v < 0.0f || u + v > 1.0f) {
                    continue;
                }

                float t = f * (e2x * qx + e2y * qy + e2z * qz);
                if (t >= minT && t <= len && t < bestT) {
                    bestT = t;
                    hit = true;
                }
            }
        }

        return hit ? bestT : Float.NaN;
    }

    private float raycastDownward(ZoneCollisionObject object, float worldX, float startY, float worldZ) {
        float[] matrix = object.toWorldSpace();
        float bestT = Float.MAX_VALUE;
        boolean hit = false;

        for (ZoneCollisionTriangle tri : object.triangles()) {
            float[] v0 = transformVertex(matrix, tri.x0(), tri.y0(), tri.z0());
            float[] v1 = transformVertex(matrix, tri.x1(), tri.y1(), tri.z1());
            float[] v2 = transformVertex(matrix, tri.x2(), tri.y2(), tri.z2());

            float e0x = v1[0] - v0[0];
            float e0y = v1[1] - v0[1];
            float e0z = v1[2] - v0[2];
            float e1x = v2[0] - v0[0];
            float e1y = v2[1] - v0[1];
            float e1z = v2[2] - v0[2];
            float nx = e0y * e1z - e0z * e1y;
            float ny = e0z * e1x - e0x * e1z;
            float nz = e0x * e1y - e0y * e1x;
            float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (normalLength < RAY_EPSILON || ny / normalLength > -FLOOR_NORMAL_Y_MIN) {
                continue;
            }

            float denom = ny;
            if (Math.abs(denom) < RAY_EPSILON) {
                continue;
            }

            float t = (nx * (v0[0] - worldX) + ny * (v0[1] - startY) + nz * (v0[2] - worldZ)) / denom;
            if (t < 0.0f || t >= bestT) {
                continue;
            }

            if (!pointInTriangle(worldX, worldZ, v0[0], v0[2], v1[0], v1[2], v2[0], v2[2])) {
                continue;
            }

            bestT = t;
            hit = true;
        }

        return hit ? startY + bestT : Float.NaN;
    }

    private static float[] transformVertex(float[] matrix, float lx, float ly, float lz) {
        float fx = matrix[0] * lx + matrix[4] * ly + matrix[8] * lz + matrix[12];
        float fy = matrix[1] * lx + matrix[5] * ly + matrix[9] * lz + matrix[13];
        float fz = matrix[2] * lx + matrix[6] * ly + matrix[10] * lz + matrix[14];
        return new float[]{fx, fy, fz};
    }

    private static boolean pointInTriangle(float px, float pz,
                                           float ax, float az,
                                           float bx, float bz,
                                           float cx, float cz) {
        float v0x = cx - ax;
        float v0z = cz - az;
        float v1x = bx - ax;
        float v1z = bz - az;
        float v2x = px - ax;
        float v2z = pz - az;
        float d00 = v0x * v0x + v0z * v0z;
        float d01 = v0x * v1x + v0z * v1z;
        float d11 = v1x * v1x + v1z * v1z;
        float d02 = v0x * v2x + v0z * v2z;
        float d12 = v1x * v2x + v1z * v2z;
        float denom = d00 * d11 - d01 * d01;
        if (Math.abs(denom) < RAY_EPSILON) {
            return false;
        }
        float inv = 1.0f / denom;
        float u = (d11 * d02 - d01 * d12) * inv;
        float v = (d00 * d12 - d01 * d02) * inv;
        return u >= 0.0f && v >= 0.0f && u + v <= 1.0f;
    }
}
