package com.example.asset.geometry;

/**
 * 4D Quaternion for 3D rotations, skeletal animation, and spherical linear interpolation (SLERP).
 */
public record Quaternion(float x, float y, float z, float w) {

    public static final Quaternion IDENTITY = new Quaternion(0.0f, 0.0f, 0.0f, 1.0f);

    public static Quaternion fromEuler(float pitch, float yaw, float roll) {
        float halfPitch = pitch * 0.5f;
        float halfYaw = yaw * 0.5f;
        float halfRoll = roll * 0.5f;

        float sinP = (float) Math.sin(halfPitch);
        float cosP = (float) Math.cos(halfPitch);
        float sinY = (float) Math.sin(halfYaw);
        float cosY = (float) Math.cos(halfYaw);
        float sinR = (float) Math.sin(halfRoll);
        float cosR = (float) Math.cos(halfRoll);

        return new Quaternion(
                sinP * cosY * cosR - cosP * sinY * sinR,
                cosP * sinY * cosR + sinP * cosY * sinR,
                cosP * cosY * sinR - sinP * sinY * cosR,
                cosP * cosY * cosR + sinP * sinY * sinR
        ).normalize();
    }

    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public Quaternion normalize() {
        float len = length();
        if (len < 1e-6f) {
            return IDENTITY;
        }
        float inv = 1.0f / len;
        return new Quaternion(x * inv, y * inv, z * inv, w * inv);
    }

    public float dot(Quaternion other) {
        return x * other.x + y * other.y + z * other.z + w * other.w;
    }

    public Quaternion multiply(Quaternion q) {
        return new Quaternion(
                w * q.x + x * q.w + y * q.z - z * q.y,
                w * q.y - x * q.z + y * q.w + z * q.x,
                w * q.z + x * q.y - y * q.x + z * q.w,
                w * q.w - x * q.x - y * q.y - z * q.z
        );
    }

    public Quaternion scale(float factor) {
        return new Quaternion(x * factor, y * factor, z * factor, w * factor);
    }

    public Quaternion add(Quaternion other) {
        return new Quaternion(x + other.x, y + other.y, z + other.z, w + other.w);
    }

    /**
     * Normalized Linear Interpolation (NLERP).
     */
    public static Quaternion nlerp(Quaternion a, Quaternion b, float t) {
        float dot = a.dot(b);
        Quaternion targetB = (dot < 0.0f) ? b.scale(-1.0f) : b;
        return a.scale(1.0f - t).add(targetB.scale(t)).normalize();
    }

    /**
     * Spherical Linear Interpolation (SLERP) for smooth rotational animation.
     */
    public static Quaternion slerp(Quaternion a, Quaternion b, float t) {
        float cosHalfTheta = a.dot(b);
        if (Math.abs(cosHalfTheta) >= 1.0f) {
            return a;
        }

        Quaternion qa = (cosHalfTheta < 0.0f) ? a.scale(-1.0f) : a;
        float absCos = Math.abs(cosHalfTheta);

        float halfTheta = (float) Math.acos(absCos);
        float sinHalfTheta = (float) Math.sin(halfTheta);

        if (Math.abs(sinHalfTheta) < 0.001f) {
            return qa.scale(0.5f).add(b.scale(0.5f)).normalize();
        }

        float ratioA = (float) Math.sin((1.0f - t) * halfTheta) / sinHalfTheta;
        float ratioB = (float) Math.sin(t * halfTheta) / sinHalfTheta;

        return qa.scale(ratioA).add(b.scale(ratioB)).normalize();
    }

    /**
     * Converts this quaternion into a 4x4 column-major rotation matrix.
     */
    public Matrix4f toMatrix4f() {
        float[] m = new float[16];

        m[0] = 1.0f - 2.0f * (y * y + z * z);
        m[1] = 2.0f * (x * y + w * z);
        m[2] = 2.0f * (x * z - w * y);
        m[3] = 0.0f;

        m[4] = 2.0f * (x * y - w * z);
        m[5] = 1.0f - 2.0f * (x * x + z * z);
        m[6] = 2.0f * (y * z + w * x);
        m[7] = 0.0f;

        m[8] = 2.0f * (x * z + w * y);
        m[9] = 2.0f * (y * z - w * x);
        m[10] = 1.0f - 2.0f * (x * x + y * y);
        m[11] = 0.0f;

        m[12] = 0.0f;
        m[13] = 0.0f;
        m[14] = 0.0f;
        m[15] = 1.0f;

        return new Matrix4f(m);
    }
}
