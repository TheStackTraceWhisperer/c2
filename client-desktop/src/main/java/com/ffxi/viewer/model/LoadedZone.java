package com.ffxi.viewer.model;

import com.ffxi.model.ZoneMeshChunk;
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
        ZoneCollisionSystem collisionSystem
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
        this(zoneId, zoneName, chunkInstances, textureSources, null);
    }

    public record ZoneChunkInstance(
            ZoneMeshChunk chunk,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold
    ) {
        public ZoneChunkInstance {
            modelMatrix = modelMatrix != null ? new Matrix4f(modelMatrix) : new Matrix4f();
        }
    }
}
