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

    /**
     * Projects a 3D world coordinate into 2D screen pixel coordinates.
     * Returns float[3] { screenX, screenY, distance } or null if behind camera or clipped.
     */
    public static float[] projectToScreen(float x, float y, float z, float[] proj, float[] view, int screenWidth, int screenHeight) {
        // Multiply by column-major view matrix: posView = view * posWorld
        float vx = view[0] * x + view[4] * y + view[8] * z + view[12];
        float vy = view[1] * x + view[5] * y + view[9] * z + view[13];
        float vz = view[2] * x + view[6] * y + view[10] * z + view[14];
        float vw = view[3] * x + view[7] * y + view[11] * z + view[15];

        // Multiply by projection matrix: clip = proj * posView
        float cx = proj[0] * vx + proj[4] * vy + proj[8] * vz + proj[12] * vw;
        float cy = proj[1] * vx + proj[5] * vy + proj[9] * vz + proj[13] * vw;
        float cw = proj[3] * vx + proj[7] * vy + proj[11] * vz + proj[15] * vw;

        if (cw <= 0.05f) {
            return null; // Behind camera
        }

        float ndcX = cx / cw;
        float ndcY = cy / cw;

        if (ndcX < -1.2f || ndcX > 1.2f || ndcY < -1.2f || ndcY > 1.2f) {
            return null; // Off screen
        }

        float sx = (ndcX + 1.0f) * 0.5f * screenWidth;
        float sy = (1.0f - ndcY) * 0.5f * screenHeight;

        return new float[]{sx, sy, cw};
    }

    /**
     * Builds a column-major model transform matrix combining translation and yaw rotation.
     * In FFXI world coordinates: 0 deg = North (+Z), 90 deg = East (+X).
     */
    public static float[] modelMatrix(float x, float y, float z, float yawDegrees) {
        float rad = (float) Math.toRadians(yawDegrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float[] m = new float[16];
        // Col 0: Right vector (1, 0, 0) rotated by yaw
        m[0] = cos;
        m[1] = 0f;
        m[2] = -sin;
        m[3] = 0f;

        // Col 1: Up vector (0, 1, 0)
        m[4] = 0f;
        m[5] = 1f;
        m[6] = 0f;
        m[7] = 0f;

        // Col 2: Forward vector (0, 0, 1) rotated by yaw
        m[8] = sin;
        m[9] = 0f;
        m[10] = cos;
        m[11] = 0f;

        // Col 3: Translation position
        m[12] = x;
        m[13] = y;
        m[14] = z;
        m[15] = 1f;

        return m;
    }
}
