package com.example.asset.mesh;

import com.example.asset.geometry.Vector3f;

import java.util.Objects;

/**
 * An instanced zone object placed in the world with translation, ZYX rotation, and scale.
 * Matches FFXI S1C_ZONE_DEF zone object definitions.
 */
public record ZoneObject(
        int index,
        String meshName,
        Vector3f position,
        Vector3f rotation,
        Vector3f scale
) {

    public ZoneObject {
        Objects.requireNonNull(meshName, "meshName must not be null");
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(rotation, "rotation must not be null");
        Objects.requireNonNull(scale, "scale must not be null");
    }

    /**
     * Builds a 4x4 column-major model matrix combining translation, ZYX rotation, and scale.
     * Matches xim: translate(pos) * rotateZYX(rot) * scale(scale).
     */
    public float[] toModelMatrix() {
        float xRad = rotation.x();
        float yRad = rotation.y();
        float zRad = rotation.z();

        float sinX = (float) Math.sin(xRad);
        float cosX = (float) Math.cos(xRad);
        float sinY = (float) Math.sin(yRad);
        float cosY = (float) Math.cos(yRad);
        float sinZ = (float) Math.sin(zRad);
        float cosZ = (float) Math.cos(zRad);

        // Rotation matrix elements (column-major)
        float r0 = cosY * cosZ;
        float r1 = cosY * sinZ;
        float r2 = -sinY;

        float r4 = sinX * sinY * cosZ - cosX * sinZ;
        float r5 = sinX * sinY * sinZ + cosX * cosZ;
        float r6 = sinX * cosY;

        float r8 = cosX * sinY * cosZ + sinX * sinZ;
        float r9 = cosX * sinY * sinZ - sinX * cosZ;
        float r10 = cosX * cosY;

        // Multiply columns by scale, and set translation
        float sx = scale.x();
        float sy = scale.y();
        float sz = scale.z();

        return new float[]{
                r0 * sx, r1 * sx, r2 * sx, 0f,
                r4 * sy, r5 * sy, r6 * sy, 0f,
                r8 * sz, r9 * sz, r10 * sz, 0f,
                position.x(), position.y(), position.z(), 1f
        };
    }

    /**
     * Checks if the object has negative scaling determinant (requiring winding flip).
     */
    public boolean isMirrored() {
        return (scale.x() * scale.y() * scale.z()) < 0f;
    }
}
