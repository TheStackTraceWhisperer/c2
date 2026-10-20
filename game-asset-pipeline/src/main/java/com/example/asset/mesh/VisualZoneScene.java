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
        List<VisualZoneMesh> meshes,
        List<ZoneObject> zoneObjects
) {
    public VisualZoneScene {
        textures = Collections.unmodifiableMap(textures);
        meshes = Collections.unmodifiableList(meshes);
        zoneObjects = (zoneObjects != null) ? Collections.unmodifiableList(zoneObjects) : List.of();
    }

    public VisualZoneScene(Map<String, DecodedTexture> textures, List<VisualZoneMesh> meshes) {
        this(textures, meshes, List.of());
    }

    public Optional<DecodedTexture> getTexture(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String clean = name.trim().toLowerCase();
        DecodedTexture tex = textures.get(clean);
        if (tex != null) {
            return Optional.of(tex);
        }
        // Try stripping 'model ' prefix
        if (clean.startsWith("model ")) {
            tex = textures.get(clean.substring(6).trim());
            if (tex != null) {
                return Optional.of(tex);
            }
        }
        // Try last word (e.g. 'model arch' -> 'arch')
        String[] parts = clean.split("\\s+");
        if (parts.length > 1) {
            tex = textures.get(parts[parts.length - 1]);
            if (tex != null) {
                return Optional.of(tex);
            }
        }
        return Optional.empty();
    }

    public Optional<VisualZoneMesh> getMesh(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        String clean = name.trim().toLowerCase();
        for (VisualZoneMesh mesh : meshes) {
            String mName = mesh.name().trim().toLowerCase();
            if (mName.equals(clean) || mName.equals(clean + "_h") || mName.equals(clean + "_m") || mName.equals(clean + "_l")) {
                return Optional.of(mesh);
            }
        }
        return Optional.empty();
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
