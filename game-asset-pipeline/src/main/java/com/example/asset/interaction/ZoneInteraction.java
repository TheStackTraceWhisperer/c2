package com.example.asset.interaction;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.Vector3f;
import lombok.Getter;
import lombok.ToString;

/**
 * Authentic FFXI zone interaction volume (Zonelines, Zone Entrances, Doors, Fishing areas).
 * Extracted from section 0x36 ("RID") of zone DAT files.
 */
@Getter
@ToString
public final class ZoneInteraction {

    private final Vector3f position;
    private final Vector3f orientation;
    private final Vector3f size;
    private final String sourceId;
    private final String destId;
    private final int param;
    private final int mapId;
    private final BoundingBox boundingBox;

    public ZoneInteraction(Vector3f position, Vector3f orientation, Vector3f size,
                           String sourceId, String destId, int param, int mapId) {
        this.position = position;
        this.orientation = orientation;
        this.size = size;
        this.sourceId = sourceId;
        this.destId = destId;
        this.param = param;
        this.mapId = mapId;

        // Compute axis-aligned bounding box around center position
        float halfX = Math.max(0.5f, Math.abs(size.x()) / 2.0f);
        float halfY = Math.max(1.0f, Math.abs(size.y()) / 2.0f);
        float halfZ = Math.max(0.5f, Math.abs(size.z()) / 2.0f);

        Vector3f min = new Vector3f(position.x() - halfX, position.y() - halfY, position.z() - halfZ);
        Vector3f max = new Vector3f(position.x() + halfX, position.y() + halfY, position.z() + halfZ);
        this.boundingBox = new BoundingBox(min, max);
    }

    public boolean isZoneLine() {
        return sourceId != null && sourceId.startsWith("z") && destId != null && !destId.isEmpty();
    }

    public boolean isZoneEntrance() {
        return sourceId != null && sourceId.startsWith("z") && (destId == null || destId.isEmpty());
    }

    public boolean isDoor() {
        return sourceId != null && sourceId.startsWith("_");
    }

    public boolean isFishingArea() {
        return sourceId != null && sourceId.startsWith("f");
    }

    /**
     * Checks if a 3D coordinate falls inside this interaction volume.
     */
    public boolean contains(float x, float y, float z) {
        return x >= boundingBox.min().x() && x <= boundingBox.max().x() &&
               y >= boundingBox.min().y() && y <= boundingBox.max().y() &&
               z >= boundingBox.min().z() && z <= boundingBox.max().z();
    }

    /**
     * Checks if a 2D horizontal coordinate (ignoring small height variances) intersects the zoneline.
     */
    public boolean containsHorizontal(float x, float z, float tolerance) {
        return x >= (boundingBox.min().x() - tolerance) && x <= (boundingBox.max().x() + tolerance) &&
               z >= (boundingBox.min().z() - tolerance) && z <= (boundingBox.max().z() + tolerance);
    }
}
