package com.example.asset.mesh;

import java.util.List;

/**
 * A renderable submesh with a specific texture material assignment.
 */
public record VisualSubMesh(
        String textureName,
        boolean blendEnabled,
        boolean backFaceCulling,
        List<ZoneMeshVertex> vertices,
        List<Integer> indices
) {

    public int triangleCount() {
        return indices.size() / 3;
    }
}
