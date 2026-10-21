package com.ffxi.model;

import java.util.List;

public record WeightedMeshChunk(
        List<Vec3f> positions,
        List<Vec3f> normals
) {
    public WeightedMeshChunk {
        positions = List.copyOf(positions != null ? positions : List.of());
        normals = List.copyOf(normals != null ? normals : List.of());
    }
}
