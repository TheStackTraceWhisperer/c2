package com.example.asset.mesh;

import com.example.asset.geometry.BoundingBox;

import java.util.List;

/**
 * A static visual zone geometry model decoded from an S2E_ZONE_MESH DAT section.
 */
public record VisualZoneMesh(
        String sectionId,
        String name,
        String author,
        BoundingBox boundingBox,
        List<VisualSubMesh> subMeshes
) {

    public int totalTriangles() {
        return subMeshes.stream().mapToInt(VisualSubMesh::triangleCount).sum();
    }

    public int totalVertices() {
        return subMeshes.stream().mapToInt(s -> s.vertices().size()).sum();
    }
}
