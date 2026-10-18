package com.example.asset.geometry;

import java.util.Objects;

/**
 * Immutable 3D single-precision floating point vector.
 */
public record Vector3f(float x, float y, float z) {

    public static final Vector3f ZERO = new Vector3f(0f, 0f, 0f);
    public static final Vector3f ONE = new Vector3f(1f, 1f, 1f);
    public static final Vector3f X = new Vector3f(1f, 0f, 0f);
    public static final Vector3f Y = new Vector3f(0f, 1f, 0f);
    public static final Vector3f Z = new Vector3f(0f, 0f, 1f);
    public static final Vector3f NEG_X = new Vector3f(-1f, 0f, 0f);
    public static final Vector3f NEG_Y = new Vector3f(0f, -1f, 0f);
    public static final Vector3f NEG_Z = new Vector3f(0f, 0f, -1f);
    public static final Vector3f UP = Y;
    public static final Vector3f DOWN = NEG_Y;

    public Vector3f add(Vector3f other) {
        return new Vector3f(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public Vector3f add(float dx, float dy, float dz) {
        return new Vector3f(this.x + dx, this.y + dy, this.z + dz);
    }

    public Vector3f sub(Vector3f other) {
        return new Vector3f(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public Vector3f mul(float scalar) {
        return new Vector3f(this.x * scalar, this.y * scalar, this.z * scalar);
    }

    public float dot(Vector3f other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public Vector3f cross(Vector3f other) {
        return new Vector3f(
                this.y * other.z - this.z * other.y,
                this.z * other.x - this.x * other.z,
                this.x * other.y - this.y * other.x
        );
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public Vector3f normalize() {
        float lenSq = lengthSquared();
        if (lenSq < 1e-12f) {
            return ZERO;
        }
        float invLen = 1.0f / (float) Math.sqrt(lenSq);
        return new Vector3f(x * invLen, y * invLen, z * invLen);
    }

    public float distanceSquared(Vector3f other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        float dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public float distance(Vector3f other) {
        return (float) Math.sqrt(distanceSquared(other));
    }
}
