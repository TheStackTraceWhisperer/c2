package com.ffxi.model;

import java.util.List;

public record ZoneSpacePartitionNode(
        boolean leafNode,
        List<Integer> containedObjects,
        ZoneSpacePartitionBounds boundingBox,
        List<ZoneSpacePartitionNode> children
) {
}
