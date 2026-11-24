package com.example.asset.collision;

import com.example.asset.geometry.Matrix4f;

import java.util.Objects;

/**
 * Coordinate space transforms and metadata for instancing a collision mesh.
 */
public record CollisionTransform(
        int fileOffset,
        Matrix4f toWorldSpace,
        Matrix4f toCollisionSpace,
        int mapId,
        int miscFlags,
        Integer subAreaLinkId,
        int lightIndicesPacked,
        String environmentId
) {

    public CollisionTransform {
        Objects.requireNonNull(toWorldSpace, "toWorldSpace must not be null");
        Objects.requireNonNull(toCollisionSpace, "toCollisionSpace must not be null");
        environmentId = environmentId != null ? environmentId : "";
    }

    /** Backwards-compatible 6-argument constructor for tests constructing synthetic collision transforms. */
    public CollisionTransform(
            int fileOffset,
            Matrix4f toWorldSpace,
            Matrix4f toCollisionSpace,
            int mapId,
            int miscFlags,
            Integer subAreaLinkId
    ) {
        this(fileOffset, toWorldSpace, toCollisionSpace, mapId, miscFlags, subAreaLinkId, 0, "");
    }

    /** Returns the 0-based zone point-light index for slot {@code 0..3}, or {@code -1} if unset. */
    public int pointLightIndex(int slot) {
        if (slot < 0 || slot > 3) {
            return -1;
        }
        int raw = (lightIndicesPacked >>> (8 * slot)) & 0xFF;
        return raw > 0 ? raw - 1 : -1;
    }
}
