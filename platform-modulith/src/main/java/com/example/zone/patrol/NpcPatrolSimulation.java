package com.example.zone.patrol;

import com.example.zone.collision.ZoneCollisionService;
import com.example.zone.spatial.SpatialGrid;
import com.example.zone.state.ZoneEntityState;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative 20 Hz simulation subsystem advancing autonomous NPC patrols,
 * executing collision-clamped waypoint navigation, facing calculation,
 * and spatial grid indexing for delta broadcast streaming.
 */
@Slf4j
@Singleton
public class NpcPatrolSimulation {

    public static final float ARRIVAL_DISTANCE = 0.5f; // 0.5 meters arrival threshold

    private final Map<Long, NpcPatrolAgent> agents = new ConcurrentHashMap<>();

    public void registerPatrol(long npcId, NpcPatrolRoute route, float moveSpeed) {
        agents.put(npcId, new NpcPatrolAgent(npcId, route, moveSpeed));
    }

    public void removePatrol(long npcId) {
        agents.remove(npcId);
    }

    public NpcPatrolAgent getAgent(long npcId) {
        return agents.get(npcId);
    }

    public int getAgentCount() {
        return agents.size();
    }

    public void clear() {
        agents.clear();
    }

    /**
     * Advances all active patrol agents by delta time dt, clamping heights to ground
     * collision terrain and updating entity state velocities and spatial grid coordinates.
     */
    public void tick(float dt,
                     long tickNumber,
                     int zoneId,
                     Map<Long, ZoneEntityState> entities,
                     SpatialGrid spatialGrid,
                     ZoneCollisionService collisionService) {
        if (agents.isEmpty()) {
            return;
        }

        for (NpcPatrolAgent agent : agents.values()) {
            if (!agent.isActive()) {
                continue;
            }

            long npcId = agent.getNpcId();
            ZoneEntityState entity = entities.get(npcId);
            if (entity == null) {
                continue;
            }

            // Handle dwell pause timer
            if (agent.getPauseTimer() > 0.0f) {
                agent.decrementPauseTimer(dt);
                if (entity.getVx() != 0f || entity.getVz() != 0f) {
                    entity.setVx(0f);
                    entity.setVy(0f);
                    entity.setVz(0f);
                    entity.setMoveFlags(0);
                    entity.setLastUpdatedTick(tickNumber);
                    entity.setDirty(true);
                }
                continue;
            }

            NpcPatrolWaypoint wp = agent.getCurrentWaypoint();
            float curX = entity.getX();
            float curY = entity.getY();
            float curZ = entity.getZ();

            float dx = wp.x() - curX;
            float dz = wp.z() - curZ;
            float dist = (float) Math.hypot(dx, dz);

            if (dist <= ARRIVAL_DISTANCE) {
                // Reached waypoint!
                if (wp.dwellTimeSeconds() > 0.0f) {
                    agent.setPauseTimer(wp.dwellTimeSeconds());
                }
                agent.advanceWaypoint();

                entity.setVx(0f);
                entity.setVy(0f);
                entity.setVz(0f);
                entity.setMoveFlags(0);
                entity.setLastUpdatedTick(tickNumber);
                entity.setDirty(true);
                continue;
            }

            // Move towards waypoint
            float dirX = dx / dist;
            float dirZ = dz / dist;
            float step = Math.min(dist, agent.getMoveSpeed() * dt);

            float nextX = curX + dirX * step;
            float nextZ = curZ + dirZ * step;

            // Facing heading in degrees: 0 = North (-Z), 90 = East (+X)
            double yawRad = Math.atan2(dirX, -dirZ);
            float yawDeg = (float) Math.toDegrees(yawRad);
            if (yawDeg < 0.0f) {
                yawDeg += 360.0f;
            }

            // Height clamping via collision service
            float nextY = wp.y();
            if (collisionService != null && collisionService.hasCollision(zoneId)) {
                ZoneCollisionService.MovementValidationResult res = collisionService.validateAndClamp(
                        zoneId,
                        curX, curY, curZ,
                        nextX, curY, nextZ
                );
                nextX = res.x();
                nextY = res.y();
                nextZ = res.z();
            }

            float vx = dirX * agent.getMoveSpeed();
            float vz = dirZ * agent.getMoveSpeed();

            entity.setX(nextX);
            entity.setY(nextY);
            entity.setZ(nextZ);
            entity.setVx(vx);
            entity.setVy(0f);
            entity.setVz(vz);
            entity.setYaw(yawDeg);
            entity.setMoveFlags(1); // Moving forward
            entity.setLastUpdatedTick(tickNumber);
            entity.setDirty(true);

            spatialGrid.updatePosition(npcId, nextX, nextZ);
        }
    }
}
