package com.example.asset.geometry;

import java.util.Arrays;

/**
 * 3x3 single-precision matrix stored in column-major order:
 * m0 m3 m6
 * m1 m4 m7
 * m2 m5 m8
 */
public final class Matrix3f {

    private final float[] m = new float[9];

    public Matrix3f() {
        identity();
    }

    public Matrix3f(float[] values) {
        if (values.length != 9) {
            throw new IllegalArgumentException("Matrix3f requires 9 floats, got " + values.length);
        }
        System.arraycopy(values, 0, this.m, 0, 9);
    }

    public Matrix3f identity() {
        Arrays.fill(m, 0.0f);
        m[0] = 1.0f;
        m[4] = 1.0f;
        m[8] = 1.0f;
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

    public Matrix3f transpose() {
        Matrix3f result = new Matrix3f();
        result.m[0] = m[0];
        result.m[1] = m[3];
        result.m[2] = m[6];

        result.m[3] = m[1];
        result.m[4] = m[4];
        result.m[5] = m[7];

        result.m[6] = m[2];
        result.m[7] = m[5];
        result.m[8] = m[8];
        return result;
    }

    public Vector3f transform(Vector3f v) {
        return new Vector3f(
                m[0] * v.x() + m[3] * v.y() + m[6] * v.z(),
                m[1] * v.x() + m[4] * v.y() + m[7] * v.z(),
                m[2] * v.x() + m[5] * v.y() + m[8] * v.z()
        );
    }
}
