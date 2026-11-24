package com.ffxi.viewer.renderer;

import org.joml.Matrix4f;

public record ZoneRenderChunk(
        ZoneMeshBuffer buffer,
        Matrix4f modelMatrix,
        boolean invertCull,
        float discardThreshold,
        int zoneObjectIndex,
        ZoneEffectParams effectParams,
        int[] pointLightIndices,
        String environmentLink
) implements AutoCloseable {

    private static final int[] NO_POINT_LIGHTS = {-1, -1, -1, -1};

    public ZoneRenderChunk {
        if (pointLightIndices == null) {
            pointLightIndices = NO_POINT_LIGHTS;
        } else {
            int[] copy = new int[]{-1, -1, -1, -1};
            System.arraycopy(pointLightIndices, 0, copy, 0, Math.min(4, pointLightIndices.length));
            pointLightIndices = copy;
        }
        environmentLink = environmentLink != null ? environmentLink : "";
    }

    public ZoneRenderChunk(
            ZoneMeshBuffer buffer,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold
    ) {
        this(buffer, modelMatrix, invertCull, discardThreshold, -1, null, NO_POINT_LIGHTS, "");
    }

    public ZoneRenderChunk(
            ZoneMeshBuffer buffer,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold,
            int zoneObjectIndex
    ) {
        this(buffer, modelMatrix, invertCull, discardThreshold, zoneObjectIndex, null, NO_POINT_LIGHTS, "");
    }

    public ZoneRenderChunk(
            ZoneMeshBuffer buffer,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold,
            int zoneObjectIndex,
            ZoneEffectParams effectParams
    ) {
        this(buffer, modelMatrix, invertCull, discardThreshold, zoneObjectIndex, effectParams, NO_POINT_LIGHTS, "");
    }

    public int pointLightIndex(int slot) {
        return (slot >= 0 && slot < pointLightIndices.length) ? pointLightIndices[slot] : -1;
    }

    public boolean hasPointLights() {
        return pointLightIndices[0] >= 0 || pointLightIndices[1] >= 0
                || pointLightIndices[2] >= 0 || pointLightIndices[3] >= 0;
    }

    public boolean isEffect() {
        return effectParams != null;
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
        }
    }
}
