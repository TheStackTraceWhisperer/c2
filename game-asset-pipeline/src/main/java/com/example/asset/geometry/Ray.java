package com.example.asset.geometry;

import java.util.Objects;

/**
 * 3D Ray with origin and direction vector.
 */
public record Ray(Vector3f origin, Vector3f direction) {

    public Ray {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
    }

    public static Ray downwards(Vector3f origin) {
        return new Ray(origin, Vector3f.DOWN);
    }

    public Vector3f at(float distance) {
        return origin.add(direction.mul(distance));
    }
}
