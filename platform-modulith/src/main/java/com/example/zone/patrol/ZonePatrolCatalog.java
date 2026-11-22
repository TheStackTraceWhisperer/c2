package com.example.zone.patrol;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Authoritative retail LandSandBoat (LSB) NPC patrol routes seeded on zone startup.
 * Coordinates use native FFXI/LSB conventions ({@code -Y = Up}, {@code +X = East}, {@code +Z = North}).
 */
public final class ZonePatrolCatalog {

    public record PatrolDefinition(NpcPatrolRoute route, float moveSpeed) {
    }

    private static final Map<Integer, Map<String, PatrolDefinition>> ROUTES_BY_ZONE = Map.of(
            230, Map.of(
                    // Retail LSB scripts/zones/Southern_San_dOria/npcs/Glenne.lua (West Gate sentry)
                    "glenne", new PatrolDefinition(
                            new NpcPatrolRoute(
                                    "zone230_glenne_patrol",
                                    List.of(
                                            new NpcPatrolWaypoint(-121.512833f, -2.0f, 14.492509f, 1.0f),
                                            new NpcPatrolWaypoint(-126.7060f, -2.0f, 14.492509f, 1.0f)
                                    ),
                                    true
                            ),
                            1.5f
                    )
            )
    );

    private ZonePatrolCatalog() {
    }

    /**
     * Resolves a seeded retail patrol definition for an NPC in {@code zoneId}, if defined.
     */
    public static Optional<PatrolDefinition> findRoute(int zoneId, String npcName) {
        if (npcName == null || npcName.isBlank()) {
            return Optional.empty();
        }
        Map<String, PatrolDefinition> zoneRoutes = ROUTES_BY_ZONE.get(zoneId);
        if (zoneRoutes == null || zoneRoutes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(zoneRoutes.get(npcName.trim().toLowerCase(Locale.ROOT)));
    }
}
