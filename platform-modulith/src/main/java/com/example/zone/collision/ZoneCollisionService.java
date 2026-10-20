package com.example.zone.collision;

import com.example.asset.collision.CollisionMap;
import com.example.asset.config.FfxiPathConfig;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.Vector3f;
import com.example.asset.parser.ZoneDefParser;
import com.example.asset.reader.DatByteReader;
import com.example.config.ZoneServerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative 3D collision detection and movement reconciliation service.
 * Loads and caches spatial collision maps for game zones, performing server-side
 * raycasting, ground clamping, slope/step validation, and obstacle penetration prevention.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneCollisionService {

    private final ZoneServerProperties properties;
    private final Map<Integer, CollisionMap> collisionMaps = new ConcurrentHashMap<>();

    public record MovementValidationResult(
            float x, float y, float z,
            boolean reconciled,
            String reason
    ) {}

    @PostConstruct
    public void init() {
        if (!properties.isCollisionEnabled()) {
            log.info("[ZONE-COLLISION] Collision validation disabled via configuration");
            return;
        }
        int zoneId = properties.getZoneId();
        loadZoneCollision(zoneId).ifPresentOrElse(
                map -> log.info("[ZONE-COLLISION] Loaded authoritative collision mesh for zone #{} ({} blocks wide)",
                        zoneId, map.numBlocksWide()),
                () -> log.warn("[ZONE-COLLISION] No collision map found for authoritative zone #{} — running unconstrained",
                        zoneId)
        );
    }

    public boolean hasCollision(int zoneId) {
        return collisionMaps.containsKey(zoneId);
    }

    public Optional<CollisionMap> getCollisionMap(int zoneId) {
        return Optional.ofNullable(collisionMaps.get(zoneId));
    }

    public Optional<CollisionMap> loadZoneCollision(int zoneId) {
        if (collisionMaps.containsKey(zoneId)) {
            return Optional.of(collisionMaps.get(zoneId));
        }

        byte[] datBytes = loadZoneDatBytes(zoneId);
        if (datBytes == null || datBytes.length == 0) {
            return Optional.empty();
        }

        try {
            DatByteReader reader = new DatByteReader(datBytes);
            Optional<CollisionMap> mapOpt = ZoneDefParser.parseFromDat(reader);
            mapOpt.ifPresent(map -> collisionMaps.put(zoneId, map));
            return mapOpt;
        } catch (Exception e) {
            log.error("[ZONE-COLLISION] Failed to parse collision geometry for zone #{}", zoneId, e);
            return Optional.empty();
        }
    }

    public MovementValidationResult validateAndClamp(
            int zoneId,
            float startX, float startY, float startZ,
            float targetX, float targetY, float targetZ
    ) {
        if (!properties.isCollisionEnabled()) {
            return new MovementValidationResult(targetX, targetY, targetZ, false, null);
        }

        CollisionMap map = collisionMaps.get(zoneId);
        if (map == null) {
            return new MovementValidationResult(targetX, targetY, targetZ, false, null);
        }

        float maxStep = properties.getMaxStepHeight();
        float maxJump = properties.getMaxJumpHeight();
        boolean reconciled = false;
        String reason = null;

        float finalX = targetX;
        float finalY = targetY;
        float finalZ = targetZ;

        // 1. Wall / Barrier Segment Check at torso height (+0.8m)
        Vector3f segStart = new Vector3f(startX, startY + 0.8f, startZ);
        Vector3f segEnd = new Vector3f(targetX, startY + 0.8f, targetZ);
        Optional<RayHit> wallHit = map.raycastSegment(segStart, segEnd, true);

        if (wallHit.isPresent()) {
            RayHit hit = wallHit.get();
            Vector3f moveDir = segEnd.sub(segStart).normalize();
            // Pull back slightly from the wall surface (0.1m buffer)
            Vector3f safePoint = hit.point().sub(moveDir.mul(0.1f));
            finalX = safePoint.x();
            finalZ = safePoint.z();
            reconciled = true;
            reason = "WALL_BLOCKED";
        }

        // 2. Downward Ground Raycast at candidate (X, Z)
        float castStartY = Math.max(startY, targetY);
        Vector3f queryPos = new Vector3f(finalX, castStartY, finalZ);
        Optional<RayHit> floorHit = map.raycastFloor(queryPos, 2.0f, 50.0f, true);

        if (floorHit.isPresent()) {
            float floorY = floorHit.get().point().y();
            float deltaY = finalY - floorY;

            if (deltaY < -0.15f) {
                // Client fell through floor -> snap to ground height
                finalY = floorY;
                reconciled = true;
                reason = "FLOOR_PENETRATION";
            } else if (deltaY <= maxStep) {
                // Ground clamping for slopes and stairs within step height allowance
                finalY = floorY;
            } else if (deltaY > maxJump) {
                // Exceeded allowable vertical jump/flight height -> snap to max jump height
                finalY = floorY + maxJump;
                reconciled = true;
                reason = "EXCEEDED_MAX_JUMP_HEIGHT";
            }
        }

        return new MovementValidationResult(finalX, finalY, finalZ, reconciled, reason);
    }

    private byte[] loadZoneDatBytes(int zoneId) {
        // 1. Check retail FFXI installation directory
        String relRom = (zoneId == 230) ? "ROM/1/31.DAT" : (zoneId == 100 ? "ROM/1/30.DAT" : null);
        if (relRom != null) {
            Optional<Path> installedPath = FfxiPathConfig.resolveRomFile(relRom);
            if (installedPath.isPresent() && Files.isRegularFile(installedPath.get())) {
                try {
                    log.info("[ZONE-COLLISION] Loading zone DAT from retail FFXI install: {}", installedPath.get().toAbsolutePath());
                    return Files.readAllBytes(installedPath.get());
                } catch (Exception e) {
                    log.warn("[ZONE-COLLISION] Error reading installed DAT at {}", installedPath.get(), e);
                }
            }
        }

        String filename = switch (zoneId) {
            case 230 -> "south_san_doria_230.DAT";
            case 100 -> "west_ronfaure_100.DAT";
            default -> "zone_" + zoneId + ".DAT";
        };

        // Try classpath resource
        String resPath = "/dats/zones/" + filename;
        try (InputStream stream = getClass().getResourceAsStream(resPath)) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        } catch (Exception ignored) {}

        // Try candidate filesystem paths
        String configuredDir = properties.getAssetPath();
        List<Path> candidates = List.of(
                Path.of(configuredDir, "zones", filename),
                Path.of(configuredDir, filename),
                Path.of("game-asset-pipeline/src/test/resources/dats/zones", filename),
                Path.of("../game-asset-pipeline/src/test/resources/dats/zones", filename),
                Path.of("src/test/resources/dats/zones", filename)
        );

        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    log.debug("[ZONE-COLLISION] Reading DAT asset from {}", p.toAbsolutePath());
                    return Files.readAllBytes(p);
                } catch (Exception e) {
                    log.warn("[ZONE-COLLISION] Error reading DAT file at {}", p, e);
                }
            }
        }

        return null;
    }
}
