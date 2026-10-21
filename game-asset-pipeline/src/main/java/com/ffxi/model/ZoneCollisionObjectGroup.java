package com.ffxi.model;

import java.util.List;

public record ZoneCollisionObjectGroup(List<ZoneCollisionObject> objects) {
    public ZoneCollisionObjectGroup {
        objects = List.copyOf(objects != null ? objects : List.of());
    }
}
