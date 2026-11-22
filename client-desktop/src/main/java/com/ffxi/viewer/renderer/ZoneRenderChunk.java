package com.ffxi.viewer.renderer;

import org.joml.Matrix4f;

public record ZoneRenderChunk(
        ZoneMeshBuffer buffer,
        Matrix4f modelMatrix,
        boolean invertCull,
        float discardThreshold,
        int zoneObjectIndex
) implements AutoCloseable {

    public ZoneRenderChunk(
            ZoneMeshBuffer buffer,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold
    ) {
        this(buffer, modelMatrix, invertCull, discardThreshold, -1);
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
        }
    }
}
