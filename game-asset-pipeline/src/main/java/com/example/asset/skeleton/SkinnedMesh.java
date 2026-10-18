package com.example.asset.skeleton;

import java.util.Collections;
import java.util.List;

/**
 * A composite skinned mesh consisting of multiple submeshes (e.g. character body piece or weapon).
 */
public record SkinnedMesh(
        String sectionId,
        List<SkinnedSubMesh> subMeshes
) {
    public SkinnedMesh {
        subMeshes = Collections.unmodifiableList(subMeshes);
    }

    public int totalVertexCount() {
        return subMeshes.stream().mapToInt(s -> s.vertices().size()).sum();
    }

    public int totalTriangleCount() {
        return subMeshes.stream().mapToInt(SkinnedSubMesh::triangleCount).sum();
    }
}
