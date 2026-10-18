package com.example.client.desktop.util;

/**
 * High-performance 4x4 matrix mathematics utilities for 3D OpenGL rendering.
 * Stores 16 single-precision floats in OpenGL standard column-major order.
 */
public final class MatrixMath {

    private MatrixMath() {
    }

    /**
     * Builds a standard OpenGL perspective projection matrix.
     */
    public static float[] perspective(float fovYDegrees, float aspect, float zNear, float zFar) {
        float[] m = new float[16];
        float fovRad = (float) Math.toRadians(fovYDegrees);
        float f = 1.0f / (float) Math.tan(fovRad * 0.5f);

        m[0] = f / aspect;
        m[5] = f;
        m[10] = (zFar + zNear) / (zNear - zFar);
        m[11] = -1.0f;
        m[14] = (2.0f * zFar * zNear) / (zNear - zFar);
        m[15] = 0.0f;

        return m;
    }

    /**
     * Builds an OpenGL view matrix looking from eye coordinates toward target coordinates.
     */
    public static float[] lookAt(
            float eyeX, float eyeY, float eyeZ,
            float targetX, float targetY, float targetZ,
            float upX, float upY, float upZ
    ) {
        // Forward vector (eye to target)
        float fx = targetX - eyeX;
        float fy = targetY - eyeY;
        float fz = targetZ - eyeZ;
        float fLen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fLen < 1e-6f) {
            fLen = 1.0f;
        }
        fx /= fLen;
        fy /= fLen;
        fz /= fLen;

        // Right vector = forward x up
        float rx = fy * upZ - fz * upY;
        float ry = fz * upX - fx * upZ;
        float rz = fx * upY - fy * upX;
        float rLen = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rLen < 1e-6f) {
            rLen = 1.0f;
        }
        rx /= rLen;
        ry /= rLen;
        rz /= rLen;

        // True up vector = right x forward
        float ux = ry * fz - rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy - ry * fx;

        float[] m = new float[16];

        // Column 0
        m[0] = rx;
        m[1] = ux;
        m[2] = -fx;
        m[3] = 0.0f;

        // Column 1
        m[4] = ry;
        m[5] = uy;
        m[6] = -fy;
        m[7] = 0.0f;

        // Column 2
        m[8] = rz;
        m[9] = uz;
        m[10] = -fz;
        m[11] = 0.0f;

        // Column 3
        m[12] = -(rx * eyeX + ry * eyeY + rz * eyeZ);
        m[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ);
        m[14] = (fx * eyeX + fy * eyeY + fz * eyeZ);
        m[15] = 1.0f;

        return m;
    }
}
