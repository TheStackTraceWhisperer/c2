package com.example.zone.patrol;

import lombok.Getter;
import lombok.Setter;

/**
 * Runtime state of an autonomous patrolling NPC agent tracking route progress and pause timers.
 */
@Getter
@Setter
public class NpcPatrolAgent {

    private final long npcId;
    private final NpcPatrolRoute route;
    private float moveSpeed;
    private int currentWaypointIndex;
    private float pauseTimer;
    private boolean active;

    public NpcPatrolAgent(long npcId, NpcPatrolRoute route, float moveSpeed) {
        this.npcId = npcId;
        this.route = route;
        this.moveSpeed = moveSpeed;
        this.currentWaypointIndex = 0;
        this.pauseTimer = 0.0f;
        this.active = true;
    }

    public NpcPatrolWaypoint getCurrentWaypoint() {
        return route.getWaypoint(currentWaypointIndex);
    }

    public void advanceWaypoint() {
        if (route.waypoints().isEmpty()) {
            return;
        }
        if (currentWaypointIndex + 1 < route.waypointCount()) {
            currentWaypointIndex++;
        } else if (route.loop()) {
            currentWaypointIndex = 0;
        } else {
            active = false;
        }
    }

    public void decrementPauseTimer(float dt) {
        if (pauseTimer > 0.0f) {
            pauseTimer = Math.max(0.0f, pauseTimer - dt);
        }
    }
}
