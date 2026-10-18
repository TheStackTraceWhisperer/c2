package com.example.asset.skeleton;

import java.util.Collections;
import java.util.List;

/**
 * A renderable skinned submesh with texture assignment and index list.
 */
public record SkinnedSubMesh(
        String textureName,
        boolean blendEnabled,
        boolean backFaceCulling,
        List<SkinnedVertex> vertices,
        List<Integer> indices
) {
    public SkinnedSubMesh {
        vertices = Collections.unmodifiableList(vertices);
        indices = Collections.unmodifiableList(indices);
    }

    public int triangleCount() {
        return indices.size() / 3;
    }
}
