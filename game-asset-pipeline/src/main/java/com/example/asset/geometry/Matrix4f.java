package com.example.asset.geometry;

import java.util.Arrays;

/**
 * 4x4 single-precision matrix stored in column-major order:
 * m0 m4 m8  m12
 * m1 m5 m9  m13
 * m2 m6 m10 m14
 * m3 m7 m11 m15
 */
public final class Matrix4f {

    private final float[] m = new float[16];

    public Matrix4f() {
        identity();
    }

    public Matrix4f(float[] values) {
        if (values.length != 16) {
            throw new IllegalArgumentException("Matrix4f requires 16 floats, got " + values.length);
        }
        System.arraycopy(values, 0, this.m, 0, 16);
    }

    public Matrix4f identity() {
        Arrays.fill(m, 0.0f);
        m[0] = 1.0f;
        m[5] = 1.0f;
        m[10] = 1.0f;
        m[15] = 1.0f;
        return this;
    }

    public float get(int index) {
        return m[index];
    }

    public void set(int index, float val) {
        m[index] = val;
    }

    public float[] elements() {
        return m.clone();
    }

    public Vector3f transform(Vector3f v) {
        return transform(v, 1.0f);
    }

    public Vector3f transform(Vector3f v, float w) {
        return new Vector3f(
                m[0] * v.x() + m[4] * v.y() + m[8] * v.z() + m[12] * w,
                m[1] * v.x() + m[5] * v.y() + m[9] * v.z() + m[13] * w,
                m[2] * v.x() + m[6] * v.y() + m[10] * v.z() + m[14] * w
        );
    }

    public Vector3f transformDirection(Vector3f v) {
        return new Vector3f(
                m[0] * v.x() + m[4] * v.y() + m[8] * v.z(),
                m[1] * v.x() + m[5] * v.y() + m[9] * v.z(),
                m[2] * v.x() + m[6] * v.y() + m[10] * v.z()
        );
    }

    public Matrix3f truncateTo3x3() {
        Matrix3f mat3 = new Matrix3f();
        mat3.set(0, m[0]);
        mat3.set(1, m[1]);
        mat3.set(2, m[2]);

        mat3.set(3, m[4]);
        mat3.set(4, m[5]);
        mat3.set(5, m[6]);

        mat3.set(6, m[8]);
        mat3.set(7, m[9]);
        mat3.set(8, m[10]);
        return mat3;
    }

    public Matrix4f multiply(Matrix4f b) {
        float[] res = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                res[c * 4 + r] =
                        m[r] * b.m[c * 4] +
                        m[4 + r] * b.m[c * 4 + 1] +
                        m[8 + r] * b.m[c * 4 + 2] +
                        m[12 + r] * b.m[c * 4 + 3];
            }
        }
        return new Matrix4f(res);
    }

    public static Matrix4f translation(float x, float y, float z) {
        Matrix4f mat = new Matrix4f();
        mat.m[12] = x;
        mat.m[13] = y;
        mat.m[14] = z;
        return mat;
    }

    public static Matrix4f scaling(float sx, float sy, float sz) {
        Matrix4f mat = new Matrix4f();
        mat.m[0] = sx;
        mat.m[5] = sy;
        mat.m[10] = sz;
        return mat;
    }

    /**
     * Computes the analytic inverse of this 4x4 matrix using cofactor expansion.
     * If the determinant is singular (near zero), returns identity matrix.
     */
    public Matrix4f invert() {
        float[] inv = new float[16];

        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15]
                + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];

        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15]
                - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];

        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15]
                + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];

        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14]
                - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];

        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15]
                - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];

        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15]
                + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];

        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15]
                - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];

        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14]
                + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];

        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15]
                + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];

        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15]
                - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];

        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15]
                + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];

        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14]
                - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];

        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11]
                - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];

        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11]
                + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];

        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11]
                - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];

        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10]
                + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];

        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];

        if (Math.abs(det) < 1e-8f) {
            return new Matrix4f(); // Fallback identity
        }

        float invDet = 1.0f / det;
        for (int i = 0; i < 16; i++) {
            inv[i] *= invDet;
        }

        return new Matrix4f(inv);
    }
}
