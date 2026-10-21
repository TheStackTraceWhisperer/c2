package com.ffxi.model;

import java.util.List;

public record ZoneMeshChunk(
        String textureName,
        boolean renderBlend,
        boolean invertCull,
        boolean isTriStrip,
        List<ZoneMeshVertex> vertices,
        List<Integer> indices
) {
    public ZoneMeshChunk {
        textureName = textureName != null ? textureName : "";
        vertices = List.copyOf(vertices != null ? vertices : List.of());
        indices = List.copyOf(indices != null ? indices : List.of());
    }
}
