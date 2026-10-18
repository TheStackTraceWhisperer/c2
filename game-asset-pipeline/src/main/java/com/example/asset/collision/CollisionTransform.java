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
        Integer subAreaLinkId
) {

    public CollisionTransform {
        Objects.requireNonNull(toWorldSpace, "toWorldSpace must not be null");
        Objects.requireNonNull(toCollisionSpace, "toCollisionSpace must not be null");
    }
}
