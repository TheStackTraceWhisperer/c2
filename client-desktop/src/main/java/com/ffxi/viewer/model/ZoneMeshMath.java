package com.ffxi.viewer.model;

import com.ffxi.model.ZoneMeshChunk;

import java.util.ArrayList;
import java.util.List;

public final class ZoneMeshMath {
    private ZoneMeshMath() {
    }

    public static boolean hasValidVertices(ZoneMeshChunk chunk) {
        List<?> vertices = chunk.vertices();
        List<Integer> indices = chunk.indices();
        if (vertices.isEmpty() || indices.isEmpty()) {
            return false;
        }
        if (!chunk.isTriStrip() && indices.size() % 3 != 0) {
            return false;
        }
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= vertices.size()) {
                return false;
            }
        }
        return true;
    }

    public static List<Integer> expandTriangles(ZoneMeshChunk chunk) {
        List<Integer> indices = chunk.indices();
        List<Integer> expanded = new ArrayList<>();
        if (chunk.isTriStrip()) {
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

    public static float[] negateXYQuaternion(float[] q) {
        return new float[]{-q[0], -q[1], q[2], q[3]};
    }

    public static float sanitizeFinite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }
}
