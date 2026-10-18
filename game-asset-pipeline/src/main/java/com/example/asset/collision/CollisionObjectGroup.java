package com.example.asset.collision;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Group of collision objects referenced by a cell in the 2D collision spatial grid.
 */
public record CollisionObjectGroup(
        int fileOffset,
        List<CollisionObject> collisionObjects
) {

    public CollisionObjectGroup {
        Objects.requireNonNull(collisionObjects, "collisionObjects must not be null");
        collisionObjects = Collections.unmodifiableList(collisionObjects);
    }
}
