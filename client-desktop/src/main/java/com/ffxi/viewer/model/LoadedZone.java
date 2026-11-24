package com.ffxi.viewer.model;

import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneSpacePartitionNode;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import com.ffxi.viewer.environment.ZoneEnvironmentTable;
import com.ffxi.viewer.environment.ZonePointLight;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import com.ffxi.viewer.renderer.ZoneEffectParams;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

public record LoadedZone(
        int zoneId,
        String zoneName,
        List<ZoneChunkInstance> chunkInstances,
        Map<String, CharacterTextureLoader.TextureSource> textureSources,
        ZoneCollisionSystem collisionSystem,
        ZoneSpacePartitionNode spacePartitioningTreeRoot,
        ZoneEnvironmentTable environmentTable,
        List<ZonePointLight> pointLights,
        Map<String, List<ZoneChunkInstance>> weatherSkyEffectsByTag
) {
    private static final int[] NO_POINT_LIGHTS = {-1, -1, -1, -1};

    public LoadedZone {
        zoneName = zoneName != null ? zoneName : "";
        chunkInstances = List.copyOf(chunkInstances != null ? chunkInstances : List.of());
        textureSources = Map.copyOf(textureSources != null ? textureSources : Map.of());
        pointLights = pointLights != null ? List.copyOf(pointLights) : List.of();
        weatherSkyEffectsByTag = weatherSkyEffectsByTag != null ? Map.copyOf(weatherSkyEffectsByTag) : Map.of();
    }

    public LoadedZone(
            int zoneId,
            String zoneName,
            List<ZoneChunkInstance> chunkInstances,
            Map<String, CharacterTextureLoader.TextureSource> textureSources
    ) {
        this(zoneId, zoneName, chunkInstances, textureSources, null, null, null, List.of(), Map.of());
    }

    public LoadedZone(
            int zoneId,
            String zoneName,
            List<ZoneChunkInstance> chunkInstances,
            Map<String, CharacterTextureLoader.TextureSource> textureSources,
            ZoneCollisionSystem collisionSystem
    ) {
        this(zoneId, zoneName, chunkInstances, textureSources, collisionSystem, null, null, List.of(), Map.of());
    }

    public LoadedZone(
            int zoneId,
            String zoneName,
            List<ZoneChunkInstance> chunkInstances,
            Map<String, CharacterTextureLoader.TextureSource> textureSources,
            ZoneCollisionSystem collisionSystem,
            ZoneSpacePartitionNode spacePartitioningTreeRoot
    ) {
        this(zoneId, zoneName, chunkInstances, textureSources, collisionSystem, spacePartitioningTreeRoot,
                null, List.of(), Map.of());
    }

    public record ZoneChunkInstance(
            ZoneMeshChunk chunk,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold,
            int zoneObjectIndex,
            ZoneEffectParams effectParams,
            int[] pointLightIndices,
            String environmentLink
    ) {
        public ZoneChunkInstance {
            modelMatrix = modelMatrix != null ? new Matrix4f(modelMatrix) : new Matrix4f();
            if (pointLightIndices == null) {
                pointLightIndices = NO_POINT_LIGHTS;
            } else {
                int[] copy = new int[]{-1, -1, -1, -1};
                System.arraycopy(pointLightIndices, 0, copy, 0, Math.min(4, pointLightIndices.length));
                pointLightIndices = copy;
            }
            environmentLink = environmentLink != null ? environmentLink : "";
        }

        public ZoneChunkInstance(
                ZoneMeshChunk chunk,
                Matrix4f modelMatrix,
                boolean invertCull,
                float discardThreshold
        ) {
            this(chunk, modelMatrix, invertCull, discardThreshold, -1, null, NO_POINT_LIGHTS, "");
        }

        public ZoneChunkInstance(
                ZoneMeshChunk chunk,
                Matrix4f modelMatrix,
                boolean invertCull,
                float discardThreshold,
                int zoneObjectIndex
        ) {
            this(chunk, modelMatrix, invertCull, discardThreshold, zoneObjectIndex, null, NO_POINT_LIGHTS, "");
        }

        public ZoneChunkInstance(
                ZoneMeshChunk chunk,
                Matrix4f modelMatrix,
                boolean invertCull,
                float discardThreshold,
                int zoneObjectIndex,
                ZoneEffectParams effectParams
        ) {
            this(chunk, modelMatrix, invertCull, discardThreshold, zoneObjectIndex, effectParams, NO_POINT_LIGHTS, "");
        }

        public boolean isEffect() {
            return effectParams != null;
        }
    }
}
