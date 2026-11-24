package com.example.asset.collision;

import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.Ray;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.RayTriangleCollider;
import com.example.asset.geometry.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 2D spatial collision grid for efficient broadphase collision querying and raycasting.
 */
public final class CollisionMap {

    private final int numBlocksWide;
    private final int numBlocksLong;
    private final int blockWidth;
    private final int blockLength;
    private final int subBlocksX;
    private final int subBlocksZ;
    private final CollisionObjectGroup[][] grid;

    public CollisionMap(
            int numBlocksWide,
            int numBlocksLong,
            int blockWidth,
            int blockLength,
            int subBlocksX,
            int subBlocksZ,
            CollisionObjectGroup[][] grid
    ) {
        this.numBlocksWide = Math.max(0, numBlocksWide);
        this.numBlocksLong = Math.max(0, numBlocksLong);
        this.blockWidth = blockWidth;
        this.blockLength = blockLength;
        // Clamp degenerate sub-block counts to 1 to avoid division by zero.
        this.subBlocksX = Math.max(1, subBlocksX);
        this.subBlocksZ = Math.max(1, subBlocksZ);
        this.grid = grid;
    }

    public int numBlocksWide() {
        return numBlocksWide;
    }

    public int numBlocksLong() {
        return numBlocksLong;
    }

    public int blockWidth() {
        return blockWidth;
    }

    public int blockLength() {
        return blockLength;
    }

    public int subBlocksX() {
        return subBlocksX;
    }

    public int subBlocksZ() {
        return subBlocksZ;
    }

    public int totalCellsX() {
        return numBlocksWide * subBlocksX;
    }

    public int totalCellsZ() {
        return numBlocksLong * subBlocksZ;
    }

    public int[] positionToBlock(Vector3f position) {
        if (position == null) {
            return new int[]{-1, -1};
        }
        return positionToBlock(position.x(), position.z());
    }

    public int[] positionToBlock(float ffxiX, float ffxiZ) {
        if (!Float.isFinite(ffxiX) || !Float.isFinite(ffxiZ)) {
            return new int[]{-1, -1};
        }
        float cellWidth = blockWidth / (float) subBlocksX;
        float cellLength = blockLength / (float) subBlocksZ;
        if (!(cellWidth > 0.0f) || !(cellLength > 0.0f)) {
            return new int[]{-1, -1};
        }

        float halfMapWidth = blockWidth * numBlocksWide / 2.0f;
        float positiveX = ffxiX + halfMapWidth;
        int xBlock = (int) (positiveX / cellWidth);

        float halfMapLength = blockLength * numBlocksLong / 2.0f;
        float positiveZ = ffxiZ + halfMapLength;
        int zBlock = (int) (positiveZ / cellLength);

        return new int[]{xBlock, zBlock};
    }

    /**
     * Returns {@code (ffxiX, ffxiZ)} of the center of sub-block cell {@code (xBlock, zBlock)}
     * in FFXI world space, or {@code null} if out of bounds or grid cell dimensions are non-positive.
     */
    public float[] getBlockCenterXZ(int xBlock, int zBlock) {
        if (xBlock < 0 || xBlock >= totalCellsX() || zBlock < 0 || zBlock >= totalCellsZ()) {
            return null;
        }
        float cellWidth = blockWidth / (float) subBlocksX;
        float cellLength = blockLength / (float) subBlocksZ;
        if (!(cellWidth > 0.0f) || !(cellLength > 0.0f)) {
            return null;
        }
        float halfMapWidth = blockWidth * numBlocksWide / 2.0f;
        float halfMapLength = blockLength * numBlocksLong / 2.0f;
        float fx = xBlock * cellWidth - halfMapWidth + cellWidth * 0.5f;
        float fz = zBlock * cellLength - halfMapLength + cellLength * 0.5f;
        return new float[]{fx, fz};
    }

    public CollisionObjectGroup getGroup(int xBlock, int zBlock) {
        if (grid == null || zBlock < 0 || zBlock >= totalCellsZ() || zBlock >= grid.length) {
            return null;
        }
        CollisionObjectGroup[] row = grid[zBlock];
        if (row == null || xBlock < 0 || xBlock >= totalCellsX() || xBlock >= row.length) {
            return null;
        }
        return row[xBlock];
    }

    public List<CollisionObjectGroup> getNearbyGroups(Vector3f position, int maxSteps) {
        if (grid == null || position == null || !Float.isFinite(position.x()) || !Float.isFinite(position.z())
                || maxSteps < 0 || blockWidth <= 0 || blockLength <= 0) {
            return List.of();
        }
        int[] block = positionToBlock(position);
        int bx = block[0];
        int bz = block[1];

        List<CollisionObjectGroup> result = new ArrayList<>();
        Set<CollisionObjectGroup> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int z = -maxSteps; z <= maxSteps; z++) {
            for (int x = -maxSteps; x <= maxSteps; x++) {
                CollisionObjectGroup group = getGroup(bx + x, bz + z);
                if (group != null && visited.add(group)) {
                    result.add(group);
                }
            }
        }
        return result;
    }

    /**
     * Collects unique collision objects along the horizontal segment from {@code (x0, z0)} to {@code (x1, z1)}
     * by sampling each cell's 3x3 neighborhood.
     */
    public List<CollisionObject> getObjectsAlongSegment(float x0, float z0, float x1, float z1) {
        if (grid == null || !Float.isFinite(x0) || !Float.isFinite(z0)
                || !Float.isFinite(x1) || !Float.isFinite(z1)
                || blockWidth <= 0 || blockLength <= 0) {
            return List.of();
        }
        float cellW = blockWidth / (float) subBlocksX;
        float cellL = blockLength / (float) subBlocksZ;
        float minCell = (cellW > 0.0f && cellL > 0.0f) ? Math.min(cellW, cellL) : 4.0f;
        float stepSize = Math.max(1.0f, minCell * 0.5f);

        float dx = x1 - x0;
        float dz = z1 - z0;
        float len = (float) Math.hypot(dx, dz);
        int steps = len > 1e-4f ? Math.max(1, Math.min(128, (int) Math.ceil(len / stepSize))) : 1;

        Set<CollisionObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<CollisionObject> result = new ArrayList<>();
        for (int s = 0; s <= steps; s++) {
            float t = (float) s / (float) steps;
            float sx = x0 + dx * t;
            float sz = z0 + dz * t;
            for (CollisionObjectGroup group : getNearbyGroups(new Vector3f(sx, 0.0f, sz), 1)) {
                for (CollisionObject obj : group.collisionObjects()) {
                    if (obj != null && seen.add(obj)) {
                        result.add(obj);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns all unique collision objects placed in this zone collision map, preserving grid encounter order.
     */
    public List<CollisionObject> allObjects() {
        if (grid == null) {
            return List.of();
        }
        Set<CollisionObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<CollisionObject> result = new ArrayList<>();
        int maxZ = Math.min(totalCellsZ(), grid.length);
        for (int z = 0; z < maxZ; z++) {
            CollisionObjectGroup[] row = grid[z];
            if (row == null) {
                continue;
            }
            int maxX = Math.min(totalCellsX(), row.length);
            for (int x = 0; x < maxX; x++) {
                CollisionObjectGroup group = row[x];
                if (group == null) {
                    continue;
                }
                for (CollisionObject obj : group.collisionObjects()) {
                    if (obj != null && seen.add(obj)) {
                        result.add(obj);
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<CollisionTriangle> allWorldSpaceTriangles() {
        List<CollisionTriangle> allTriangles = new ArrayList<>();
        for (CollisionObject obj : allObjects()) {
            allTriangles.addAll(obj.worldSpaceTriangles());
        }
        return Collections.unmodifiableList(allTriangles);
    }

    /**
     * Casts a downward ray (+Y) at (position.x, position.y - rayHeightOffset, position.z) to find
     * the floor height beneath the position in native FFXI coordinates (-Y = Up, +Y = Down).
     *
     * @param position query location
     * @param rayHeightOffset extra height subtracted from query Y to start raycast above ground (e.g. 2.0f)
     * @param maxDistance maximum downward ray distance ({@link Float#POSITIVE_INFINITY} for unbounded)
     * @param ignoreWalls whether to ignore vertical/wall triangles
     * @return nearest floor RayHit if found
     */
    public Optional<RayHit> raycastFloor(
            Vector3f position,
            float rayHeightOffset,
            float maxDistance,
            boolean ignoreWalls
    ) {
        return raycastFloorInternal(position, rayHeightOffset, maxDistance, ignoreWalls)
                .map(FloorHitResult::hit);
    }

    /**
     * Casts a downward floor ray matching {@link #raycastFloor(Vector3f, float, float, boolean)} and
     * returns the {@link CollisionTransform} attached to the closest non-wall floor triangle hit.
     */
    public Optional<CollisionTransform> findFloorTransform(
            Vector3f position,
            float rayHeightOffset,
            float maxDistance
    ) {
        return raycastFloorInternal(position, rayHeightOffset, maxDistance, true)
                .map(FloorHitResult::transform);
    }

    private record FloorHitResult(RayHit hit, CollisionTransform transform) {
    }

    private Optional<FloorHitResult> raycastFloorInternal(
            Vector3f position,
            float rayHeightOffset,
            float maxDistance,
            boolean ignoreWalls
    ) {
        if (grid == null || position == null
                || !Float.isFinite(position.x()) || !Float.isFinite(position.y()) || !Float.isFinite(position.z())
                || !Float.isFinite(rayHeightOffset) || Float.isNaN(maxDistance) || maxDistance < 0.0f) {
            return Optional.empty();
        }

        Vector3f rayOrigin = new Vector3f(position.x(), position.y() - rayHeightOffset, position.z());
        Ray ray = Ray.downwards(rayOrigin);
        Float maxDistBoxed = Float.isFinite(maxDistance) ? maxDistance : null;

        List<CollisionObjectGroup> groups = getNearbyGroups(position, 1);
        if (groups.isEmpty()) {
            groups = getNearbyGroups(position, 2);
        }

        Set<CollisionObject> seenObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        RayHit closestHit = null;
        CollisionTransform closestTransform = null;

        for (CollisionObjectGroup group : groups) {
            for (CollisionObject obj : group.collisionObjects()) {
                if (obj == null || !seenObjects.add(obj)) {
                    continue;
                }
                for (CollisionTriangle tri : obj.worldSpaceTriangles()) {
                    if (ignoreWalls && (tri.flags().isHitWall() || tri.normal().y() > -0.3f)) {
                        continue;
                    }
                    Optional<RayHit> hit = RayTriangleCollider.intersect(
                            tri,
                            ray.origin(),
                            ray.direction(),
                            false,
                            maxDistBoxed
                    );
                    if (hit.isPresent()) {
                        if (closestHit == null || hit.get().distance() < closestHit.distance()) {
                            closestHit = hit.get();
                            closestTransform = obj.transform();
                        }
                    }
                }
            }
        }

        return closestHit != null ? Optional.of(new FloorHitResult(closestHit, closestTransform)) : Optional.empty();
    }

    /**
     * Casts a ray along a 3D line segment between start and end to detect obstacle/wall collisions.
     *
     * @param start start point of the segment
     * @param end end point of the segment
     * @param wallsOnly whether to test only triangles flagged with {@code TriFlags.isHitWall()}
     * @return nearest RayHit along the segment if an obstacle is encountered
     */
    public Optional<RayHit> raycastSegment(Vector3f start, Vector3f end, boolean wallsOnly) {
        return raycastSegment(start, end, 0.0f, wallsOnly ? tri -> tri.flags().isHitWall() : null);
    }

    /**
     * Casts a ray along a 3D line segment between {@code start} and {@code end}, returning the closest
     * intersection whose distance from {@code start} is {@code >= minDistance}.
     *
     * @param start start point of the segment
     * @param end end point of the segment
     * @param minDistance minimum distance from {@code start} to accept a hit
     * @param triangleFilter optional predicate to restrict tested world-space triangles (null tests all triangles)
     * @return nearest matching RayHit along the segment if an intersection occurs
     */
    public Optional<RayHit> raycastSegment(
            Vector3f start,
            Vector3f end,
            float minDistance,
            Predicate<CollisionTriangle> triangleFilter
    ) {
        if (grid == null || start == null || end == null
                || !Float.isFinite(start.x()) || !Float.isFinite(start.y()) || !Float.isFinite(start.z())
                || !Float.isFinite(end.x()) || !Float.isFinite(end.y()) || !Float.isFinite(end.z())
                || !Float.isFinite(minDistance)) {
            return Optional.empty();
        }

        Vector3f diff = end.sub(start);
        float len = diff.length();
        if (len < 1e-5f) {
            return Optional.empty();
        }
        Vector3f dir = diff.div(len);

        List<CollisionObject> objects = getObjectsAlongSegment(start.x(), start.z(), end.x(), end.z());
        RayHit closestHit = null;

        for (CollisionObject obj : objects) {
            for (CollisionTriangle tri : obj.worldSpaceTriangles()) {
                if (triangleFilter != null && !triangleFilter.test(tri)) {
                    continue;
                }
                Optional<RayHit> hit = RayTriangleCollider.intersect(
                        tri,
                        start,
                        dir,
                        false,
                        len
                );
                if (hit.isPresent() && hit.get().distance() >= minDistance) {
                    if (closestHit == null || hit.get().distance() < closestHit.distance()) {
                        closestHit = hit.get();
                    }
                }
            }
        }
        return Optional.ofNullable(closestHit);
    }
}
