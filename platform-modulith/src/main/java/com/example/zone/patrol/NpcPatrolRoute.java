package com.example.zone.patrol;

import java.util.Collections;
import java.util.List;

/**
 * Sequential waypoint path for an autonomous NPC or town sentry.
 */
public record NpcPatrolRoute(
    String routeId,
    List<NpcPatrolWaypoint> waypoints,
    boolean loop
) {
    public NpcPatrolRoute {
        waypoints = Collections.unmodifiableList(waypoints);
    }

    public NpcPatrolWaypoint getWaypoint(int index) {
        if (waypoints.isEmpty()) {
            return new NpcPatrolWaypoint(0f, 0f, 0f);
        }
        return waypoints.get(Math.clamp(index, 0, waypoints.size() - 1));
    }

    public int waypointCount() {
        return waypoints.size();
    }
}
