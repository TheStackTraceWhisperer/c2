package com.ffxi.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ZoneCollisionMap {
    private final int numBlocksX;
    private final int numBlocksZ;
    private final int blockWidth;
    private final int blockLength;
    private final int subBlocksX;
    private final int subBlocksZ;
    private final ZoneCollisionObjectGroup[][] grid;

    public ZoneCollisionMap(int numBlocksWide, int numBlocksLong,
                            int blockWidth, int blockLength,
                            int subBlocksX, int subBlocksZ,
                            ZoneCollisionObjectGroup[][] grid) {
        this.numBlocksX = numBlocksWide * subBlocksX;
        this.numBlocksZ = numBlocksLong * subBlocksZ;
        this.blockWidth = blockWidth;
        this.blockLength = blockLength;
        this.subBlocksX = subBlocksX;
        this.subBlocksZ = subBlocksZ;
        this.grid = grid;
    }

    /** Get collision groups that contain (ffxiX, ffxiZ), searching a 3x3 neighbourhood. */
    public List<ZoneCollisionObjectGroup> getGroups(float ffxiX, float ffxiZ) {
        int[] xz = positionToBlock(ffxiX, ffxiZ);
        int bx = xz[0];
        int bz = xz[1];
        Set<ZoneCollisionObjectGroup> result = new LinkedHashSet<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ZoneCollisionObjectGroup group = getGroup(bx + dx, bz + dz);
                if (group != null) {
                    result.add(group);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private int[] positionToBlock(float fx, float fz) {
        int numBlocksWide = numBlocksX / subBlocksX;
        int numBlocksLong = numBlocksZ / subBlocksZ;
        float halfW = (float) (blockWidth * numBlocksWide) / 2.0f;
        float halfL = (float) (blockLength * numBlocksLong) / 2.0f;
        float cellW = (float) blockWidth / subBlocksX;
        float cellL = (float) blockLength / subBlocksZ;
        int bx = (int) ((fx + halfW) / cellW);
        int bz = (int) ((fz + halfL) / cellL);
        return new int[]{bx, bz};
    }

    private ZoneCollisionObjectGroup getGroup(int bx, int bz) {
        if (bx < 0 || bx >= numBlocksX || bz < 0 || bz >= numBlocksZ) {
            return null;
        }
        return grid[bz][bx];
    }

    /** Returns (ffxiX, ffxiZ) of the center of block (bx, bz) in FFXI world space. */
    public float[] getBlockCenterXZ(int bx, int bz) {
        if (bx < 0 || bx >= numBlocksX || bz < 0 || bz >= numBlocksZ) {
            return null;
        }
        int numBlocksWide = numBlocksX / subBlocksX;
        int numBlocksLong = numBlocksZ / subBlocksZ;
        float halfW = (float) (blockWidth * numBlocksWide) / 2.0f;
        float halfL = (float) (blockLength * numBlocksLong) / 2.0f;
        float cellW = (float) blockWidth / subBlocksX;
        float cellL = (float) blockLength / subBlocksZ;
        float fx = bx * cellW - halfW + cellW / 2.0f;
        float fz = bz * cellL - halfL + cellL / 2.0f;
        return new float[]{fx, fz};
    }

    public int getNumBlocksX() {
        return numBlocksX;
    }

    public int getNumBlocksZ() {
        return numBlocksZ;
    }

    /** Returns all unique collision objects placed in this zone collision map. */
    public List<ZoneCollisionObject> allObjects() {
        Set<ZoneCollisionObject> unique = new LinkedHashSet<>();
        for (int z = 0; z < numBlocksZ; z++) {
            for (int x = 0; x < numBlocksX; x++) {
                ZoneCollisionObjectGroup g = grid[z][x];
                if (g != null) {
                    unique.addAll(g.objects());
                }
            }
        }
        return new ArrayList<>(unique);
    }
}
