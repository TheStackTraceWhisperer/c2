package com.example.asset.collision;

import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.Ray;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.RayTriangleCollider;
import com.example.asset.geometry.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        this.numBlocksWide = numBlocksWide;
        this.numBlocksLong = numBlocksLong;
        this.blockWidth = blockWidth;
        this.blockLength = blockLength;
        this.subBlocksX = subBlocksX;
        this.subBlocksZ = subBlocksZ;
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
        return positionToBlock(-position.x(), position.z());
    }

    public int[] positionToBlock(float ffxiX, float ffxiZ) {
        float halfMapWidth = blockWidth * numBlocksWide / 2.0f;
        float positiveX = ffxiX + halfMapWidth;
        float cellWidth = blockWidth / (float) subBlocksX;
        int xBlock = (int) (positiveX / cellWidth);

        float halfMapLength = blockLength * numBlocksLong / 2.0f;
        float positiveZ = ffxiZ + halfMapLength;
        float cellLength = blockLength / (float) subBlocksZ;
        int zBlock = (int) (positiveZ / cellLength);

        return new int[]{xBlock, zBlock};
    }

    public CollisionObjectGroup getGroup(int xBlock, int zBlock) {
        if (zBlock < 0 || zBlock >= grid.length) {
            return null;
        }
        if (xBlock < 0 || xBlock >= grid[zBlock].length) {
            return null;
        }
        return grid[zBlock][xBlock];
    }

    public List<CollisionObjectGroup> getNearbyGroups(Vector3f position, int maxSteps) {
        int[] block = positionToBlock(position);
        int bx = block[0];
        int bz = block[1];

        List<CollisionObjectGroup> result = new ArrayList<>();
        Set<Integer> visitedOffsets = new HashSet<>();

        for (int z = -maxSteps; z <= maxSteps; z++) {
            for (int x = -maxSteps; x <= maxSteps; x++) {
                CollisionObjectGroup group = getGroup(bx + x, bz + z);
                if (group != null && visitedOffsets.add(group.fileOffset())) {
                    result.add(group);
                }
            }
        }
        return result;
    }

    public List<CollisionTriangle> allWorldSpaceTriangles() {
        Set<Integer> visitedObjects = new HashSet<>();
        List<CollisionTriangle> allTriangles = new ArrayList<>();

        for (CollisionObjectGroup[] row : grid) {
            for (CollisionObjectGroup group : row) {
                if (group == null) {
                    continue;
                }
                for (CollisionObject obj : group.collisionObjects()) {
                    if (visitedObjects.add(obj.fileOffset())) {
                        allTriangles.addAll(obj.worldSpaceTriangles());
                    }
                }
            }
        }
        return Collections.unmodifiableList(allTriangles);
    }

    /**
     * Casts a downward ray at (position.x, position.y + rayHeightOffset, position.z) to find
     * the floor height beneath the position.
     *
     * @param position query location
     * @param rayHeightOffset extra height added to query position to start raycast above ground (e.g. 2.0f)
     * @param maxDistance maximum downward ray distance
     * @param ignoreWalls whether to ignore vertical/wall triangles
     * @return nearest floor RayHit if found
     */
    public Optional<RayHit> raycastFloor(
            Vector3f position,
            float rayHeightOffset,
            float maxDistance,
            boolean ignoreWalls
    ) {
        Vector3f rayOrigin = new Vector3f(position.x(), position.y() + rayHeightOffset, position.z());
        Ray ray = Ray.downwards(rayOrigin);

        List<CollisionObjectGroup> groups = getNearbyGroups(position, 1);
        if (groups.isEmpty()) {
            groups = getNearbyGroups(position, 2);
        }

        RayHit closestHit = null;

        for (CollisionObjectGroup group : groups) {
            for (CollisionObject obj : group.collisionObjects()) {
                for (CollisionTriangle tri : obj.worldSpaceTriangles()) {
                    if (ignoreWalls && (tri.flags().isHitWall() || tri.normal().y() < 0.3f)) {
                        continue;
                    }
                    Optional<RayHit> hit = RayTriangleCollider.intersect(
                            tri,
                            ray.origin(),
                            ray.direction(),
                            false,
                            maxDistance
                    );
                    if (hit.isPresent()) {
                        if (closestHit == null || hit.get().distance() < closestHit.distance()) {
                            closestHit = hit.get();
                        }
                    }
                }
            }
        }

        return Optional.ofNullable(closestHit);
    }

    /**
     * Casts a ray along a 3D line segment between start and end to detect obstacle/wall collisions.
     *
     * @param start start point of the segment
     * @param end end point of the segment
     * @param wallsOnly whether to test only wall triangles
     * @return nearest RayHit along the segment if an obstacle is encountered
     */
    public Optional<RayHit> raycastSegment(Vector3f start, Vector3f end, boolean wallsOnly) {
        Vector3f diff = end.sub(start);
        float len = diff.length();
        if (len < 1e-5f) {
            return Optional.empty();
        }
        Vector3f dir = diff.div(len);

        List<CollisionObjectGroup> groups = getNearbyGroups(start, 2);
        RayHit closestHit = null;

        for (CollisionObjectGroup group : groups) {
            for (CollisionObject obj : group.collisionObjects()) {
                for (CollisionTriangle tri : obj.worldSpaceTriangles()) {
                    if (wallsOnly && !tri.flags().isHitWall()) {
                        continue;
                    }
                    Optional<RayHit> hit = RayTriangleCollider.intersect(
                            tri,
                            start,
                            dir,
                            false,
                            len
                    );
                    if (hit.isPresent()) {
                        if (closestHit == null || hit.get().distance() < closestHit.distance()) {
                            closestHit = hit.get();
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(closestHit);
    }
}
