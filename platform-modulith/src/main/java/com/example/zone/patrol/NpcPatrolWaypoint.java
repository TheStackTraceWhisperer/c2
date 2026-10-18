package com.example.zone.patrol;

/**
 * 3D spatial waypoint on an NPC patrol path with an optional dwell/pause duration.
 */
public record NpcPatrolWaypoint(
    float x,
    float y,
    float z,
    float dwellTimeSeconds
) {
    public NpcPatrolWaypoint(float x, float y, float z) {
        this(x, y, z, 0.0f);
    }
}
