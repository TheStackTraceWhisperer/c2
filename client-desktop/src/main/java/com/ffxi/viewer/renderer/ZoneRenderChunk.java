package com.ffxi.viewer.renderer;

import org.joml.Matrix4f;

public record ZoneRenderChunk(
        ZoneMeshBuffer buffer,
        Matrix4f modelMatrix,
        boolean invertCull,
        float discardThreshold
) implements AutoCloseable {

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
        }
    }
}
