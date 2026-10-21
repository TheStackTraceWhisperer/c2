package com.ffxi.model;

import java.util.List;

public record ZoneMeshResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int mode,
        int totalSize,
        int keyIndex,
        boolean secondPassFlag,
        String meshName,
        List<ZoneMeshChunk> meshChunks,
        List<ZoneMeshChunk> collisionChunks
) implements ResourceNode {
    public ZoneMeshResource {
        meshName = meshName != null ? meshName : "";
        meshChunks = List.copyOf(meshChunks != null ? meshChunks : List.of());
        collisionChunks = List.copyOf(collisionChunks != null ? collisionChunks : List.of());
    }
}
