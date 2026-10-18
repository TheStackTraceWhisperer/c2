package com.example.client.interp;

/**
 * High-precision, jitter-free interpolated render state for a remote entity at the current frame time.
 */
public record InterpolatedEntityState(
    float x, float y, float z,
    float vx, float vy, float vz,
    float yaw, float pitch,
    int moveFlags,
    boolean extrapolating
) {
    public float speedHorizontal() {
        return (float) Math.hypot(vx, vz);
    }
}
