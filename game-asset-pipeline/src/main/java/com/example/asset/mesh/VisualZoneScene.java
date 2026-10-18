package com.example.asset.mesh;

import com.example.asset.texture.DecodedTexture;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Encapsulates all decoded visual geometry and textures for a zone.
 */
public record VisualZoneScene(
        Map<String, DecodedTexture> textures,
        List<VisualZoneMesh> meshes
) {
    public VisualZoneScene {
        textures = Collections.unmodifiableMap(textures);
        meshes = Collections.unmodifiableList(meshes);
    }

    public Optional<DecodedTexture> getTexture(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(textures.get(name.trim().toLowerCase()));
    }

    public int totalMeshCount() {
        return meshes.size();
    }

    public int totalSubMeshCount() {
        return meshes.stream().mapToInt(m -> m.subMeshes().size()).sum();
    }

    public int totalVertexCount() {
        return meshes.stream()
                .flatMap(m -> m.subMeshes().stream())
                .mapToInt(s -> s.vertices().size())
                .sum();
    }

    public int totalTriangleCount() {
        return meshes.stream()
                .flatMap(m -> m.subMeshes().stream())
                .mapToInt(VisualSubMesh::triangleCount)
                .sum();
    }
}
