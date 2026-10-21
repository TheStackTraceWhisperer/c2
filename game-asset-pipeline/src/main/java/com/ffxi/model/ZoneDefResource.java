package com.ffxi.model;

import java.util.List;

public record ZoneDefResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int mode,
        int decodeLength,
        int nodeCount,
        int keyIndex,
        List<ZoneObject> zoneObjects,
        int collisionMeshOffset,
        int zoneBlocksX,
        int zoneBlocksZ,
        int blockWidth,
        int blockLength,
        int subBlocksX,
        int subBlocksZ,
        int spacePartitioningTreeOffset,
        int cullingTablesOffset,
        int pointLightOffset,
        List<List<Integer>> cullingTables,
        ZoneSpacePartitionNode spacePartitioningTreeRoot,
        List<String> pointLightLinks,
        ZoneCollisionMap collisionMap
) implements ResourceNode {
    public ZoneDefResource {
        zoneObjects = List.copyOf(zoneObjects != null ? zoneObjects : List.of());
        cullingTables = List.copyOf(cullingTables != null ? cullingTables : List.of());
        pointLightLinks = List.copyOf(pointLightLinks != null ? pointLightLinks : List.of());
    }

    public ZoneDefResource(
            String sectionId,
            String sectionType,
            int sectionStart,
            int sectionSize,
            int mode,
            int decodeLength,
            int nodeCount,
            int keyIndex,
            List<ZoneObject> zoneObjects
    ) {
        this(sectionId, sectionType, sectionStart, sectionSize, mode, decodeLength, nodeCount, keyIndex, zoneObjects,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null, List.of(), null);
    }
}
