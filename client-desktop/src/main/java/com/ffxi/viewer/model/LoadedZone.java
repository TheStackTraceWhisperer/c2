package com.ffxi.viewer.model;

import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneSpacePartitionNode;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

public record LoadedZone(
        int zoneId,
        String zoneName,
        List<ZoneChunkInstance> chunkInstances,
        Map<String, CharacterTextureLoader.TextureSource> textureSources,
        ZoneCollisionSystem collisionSystem,
        ZoneSpacePartitionNode spacePartitioningTreeRoot
) {
    public LoadedZone {
        zoneName = zoneName != null ? zoneName : "";
        chunkInstances = List.copyOf(chunkInstances != null ? chunkInstances : List.of());
        textureSources = Map.copyOf(textureSources != null ? textureSources : Map.of());
    }

    public LoadedZone(
            int zoneId,
            String zoneName,
            List<ZoneChunkInstance> chunkInstances,
            Map<String, CharacterTextureLoader.TextureSource> textureSources
    ) {
        this(zoneId, zoneName, chunkInstances, textureSources, null, null);
    }

    public LoadedZone(
            int zoneId,
            String zoneName,
            List<ZoneChunkInstance> chunkInstances,
            Map<String, CharacterTextureLoader.TextureSource> textureSources,
            ZoneCollisionSystem collisionSystem
    ) {
        this(zoneId, zoneName, chunkInstances, textureSources, collisionSystem, null);
    }

    public record ZoneChunkInstance(
            ZoneMeshChunk chunk,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold,
            int zoneObjectIndex
    ) {
        public ZoneChunkInstance {
            modelMatrix = modelMatrix != null ? new Matrix4f(modelMatrix) : new Matrix4f();
        }

        public ZoneChunkInstance(
                ZoneMeshChunk chunk,
                Matrix4f modelMatrix,
                boolean invertCull,
                float discardThreshold
        ) {
            this(chunk, modelMatrix, invertCull, discardThreshold, -1);
        }
    }
}
