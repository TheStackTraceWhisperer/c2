package com.example.asset.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared math utilities for FFXI zone geometry, coordinate systems, and winding conversion.
 * Replicates exact reference math from ffxi-xim-analysis.
 */
public final class ZoneMeshMath {

    private ZoneMeshMath() {
    }

    public static boolean hasValidVertices(int vertexCount, boolean isTriStrip, List<Integer> indices) {
        if (vertexCount == 0 || indices == null || indices.isEmpty()) {
            return false;
        }
        if (!isTriStrip && indices.size() % 3 != 0) {
            return false;
        }
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= vertexCount) {
                return false;
            }
        }
        return true;
    }

    /**
     * Expands raw DAT indices (either triangle strip or triangle list) into counter-clockwise (CCW)
     * triangles compatible with glTF and standard OpenGL.
     *
     * In FFXI, triangle lists are clockwise [i0, i1, i2]. Converting to CCW requires swapping i1 and i2: [i0, i2, i1].
     * For triangle strips, alternating parity: (i & 1) == 0 ? [i-2, i, i-1] : [i-1, i, i-2].
     */
    public static List<Integer> expandTriangles(boolean isTriStrip, List<Integer> indices) {
        if (indices == null || indices.size() < 3) {
            return List.of();
        }
        List<Integer> expanded = new ArrayList<>();
        if (isTriStrip) {
            for (int i = 2; i < indices.size(); i++) {
                int a;
                int b;
                int c;
                if ((i & 1) == 0) {
                    a = indices.get(i - 2);
                    b = indices.get(i);
                    c = indices.get(i - 1);
                } else {
                    a = indices.get(i - 1);
                    b = indices.get(i);
                    c = indices.get(i - 2);
                }
                if (a == b || b == c || a == c) {
                    continue;
                }
                expanded.add(a);
                expanded.add(b);
                expanded.add(c);
            }
            return expanded;
        }

        for (int i = 0; i + 2 < indices.size(); i += 3) {
            int i0 = indices.get(i);
            int i1 = indices.get(i + 1);
            int i2 = indices.get(i + 2);
            if (i0 == i1 || i1 == i2 || i0 == i2) {
                continue;
            }
            expanded.add(i0);
            expanded.add(i2);
            expanded.add(i1);
        }
        return expanded;
    }

    /**
     * Constructs a quaternion from ZYX Euler rotation angles (radians).
     */
    public static float[] quaternionFromZYXEuler(float rotX, float rotY, float rotZ) {
        float hx = sanitizeFinite(rotX) * 0.5f;
        float hy = sanitizeFinite(rotY) * 0.5f;
        float hz = sanitizeFinite(rotZ) * 0.5f;

        float cx = (float) Math.cos(hx);
        float sx = (float) Math.sin(hx);
        float cy = (float) Math.cos(hy);
        float sy = (float) Math.sin(hy);
        float cz = (float) Math.cos(hz);
        float sz = (float) Math.sin(hz);

        float qx = cz * cy * sx - sz * sy * cx;
        float qy = cz * sy * cx + sz * cy * sx;
        float qz = sz * cy * cx - cz * sy * sx;
        float qw = cz * cy * cx + sz * sy * sx;
        float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (length < 1.0e-6f) {
            return new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        }
        return new float[]{qx / length, qy / length, qz / length, qw / length};
    }

    /**
     * Inverts X and Y components of quaternion to map FFXI visual-up (-Y) to standard Y-up.
     */
    public static float[] negateXYQuaternion(float[] q) {
        return new float[]{-q[0], -q[1], q[2], q[3]};
    }

    /**
     * Transforms local vertex coordinates using a 4x4 matrix and converts to glTF/world space by negating X and Y.
     */
    public static float[] transformVertexToWorld(float[] matrix, float lx, float ly, float lz) {
        float fx = matrix[0] * lx + matrix[4] * ly + matrix[8] * lz + matrix[12];
        float fy = matrix[1] * lx + matrix[5] * ly + matrix[9] * lz + matrix[13];
        float fz = matrix[2] * lx + matrix[6] * ly + matrix[10] * lz + matrix[14];
        return new float[]{-fx, -fy, fz};
    }

    /**
     * Transforms local normal vector using 4x4 matrix and converts to glTF/world space by negating X and Y.
     */
    public static float[] transformNormalToWorld(float[] matrix, float nx, float ny, float nz) {
        float fx = matrix[0] * nx + matrix[4] * ny + matrix[8] * nz;
        float fy = matrix[1] * nx + matrix[5] * ny + matrix[9] * nz;
        float fz = matrix[2] * nx + matrix[6] * ny + matrix[10] * nz;
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > 1.0e-6f) {
            fx /= len;
            fy /= len;
            fz /= len;
        }
        return new float[]{-fx, -fy, fz};
    }

    public static float sanitizeFinite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }
}
