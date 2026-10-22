package com.ffxi.viewer.collision;

import com.ffxi.model.ZoneCollisionMap;
import com.ffxi.model.ZoneCollisionObject;
import com.ffxi.model.ZoneCollisionObjectGroup;
import com.ffxi.model.ZoneCollisionTriangle;

import java.util.ArrayList;
import java.util.List;

public final class ZoneCollisionSystem {

    private static final float FLOOR_NORMAL_Y_MIN = 0.5f;
    private static final float RAY_EPSILON = 1.0e-10f;
    private static final int MAX_FIND_ANY_FLOOR_TESTS = 1000;

    private final ZoneCollisionMap collisionMap;

    public ZoneCollisionSystem(ZoneCollisionMap collisionMap) {
        this.collisionMap = collisionMap;
    }

    /**
     * Finds the highest floor Y in our world space at (worldX, worldZ)
     * by casting a ray downward from (worldX, startY, worldZ).
     */
    public float findFloorY(float worldX, float worldZ, float startY) {
        if (collisionMap == null || !Float.isFinite(worldX) || !Float.isFinite(worldZ) || !Float.isFinite(startY)) {
            return Float.NaN;
        }

        float ffxiX = -worldX;
        float ffxiZ = worldZ;
        List<ZoneCollisionObjectGroup> groups = collisionMap.getGroups(ffxiX, ffxiZ);

        float bestFloorY = Float.NEGATIVE_INFINITY;
        boolean found = false;
        for (ZoneCollisionObjectGroup group : groups) {
            for (ZoneCollisionObject object : group.objects()) {
                float hit = raycastDownward(object, worldX, startY, worldZ);
                if (Float.isFinite(hit) && hit <= startY && hit > bestFloorY) {
                    bestFloorY = hit;
                    found = true;
                }
            }
        }
        return found ? bestFloorY : Float.NaN;
    }

    public float findSpawnFloorY() {
        float y = findFloorY(0.0f, 0.0f, 2000.0f);
        if (Float.isFinite(y)) {
            return y;
        }

        float[] offsets = {-50.0f, 50.0f, -100.0f, 100.0f, -200.0f, 200.0f};
        for (float ox : offsets) {
            for (float oz : offsets) {
                y = findFloorY(ox, oz, 2000.0f);
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
                    float worldX = -ffxiXZ[0];
                    float worldZ = ffxiXZ[1];
                    float worldY = findFloorY(worldX, worldZ, 2000.0f);
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
            if (normalLength < RAY_EPSILON || ny / normalLength < FLOOR_NORMAL_Y_MIN) {
                continue;
            }

            float denom = -ny;
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

        return hit ? startY - bestT : Float.NaN;
    }

    private static float[] transformVertex(float[] matrix, float lx, float ly, float lz) {
        float fx = matrix[0] * lx + matrix[4] * ly + matrix[8] * lz + matrix[12];
        float fy = matrix[1] * lx + matrix[5] * ly + matrix[9] * lz + matrix[13];
        float fz = matrix[2] * lx + matrix[6] * ly + matrix[10] * lz + matrix[14];
        return new float[]{-fx, -fy, fz};
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
