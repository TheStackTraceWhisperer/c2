package com.example.client.interp;

/**
 * Immutable timestamped kinematic snapshot of an entity received from the authoritative zone server.
 */
public record InterpolationSnapshot(
    long timestampMs,
    float x, float y, float z,
    float vx, float vy, float vz,
    float yaw, float pitch,
    int moveFlags
) {}
