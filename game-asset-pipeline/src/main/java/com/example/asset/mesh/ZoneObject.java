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
     * Matches xim reference: translate(-pos.x, -pos.y, pos.z) * rotate(negateXY(quat)) * scale(scale).
     */
    public float[] toModelMatrix() {
        float[] q = ZoneMeshMath.negateXYQuaternion(
                ZoneMeshMath.quaternionFromZYXEuler(rotation.x(), rotation.y(), rotation.z())
        );
        org.joml.Matrix4f mat = new org.joml.Matrix4f()
                .translate(ZoneMeshMath.sanitizeFinite(-position.x()),
                           ZoneMeshMath.sanitizeFinite(-position.y()),
                           ZoneMeshMath.sanitizeFinite(position.z()))
                .rotate(new org.joml.Quaternionf(q[0], q[1], q[2], q[3]))
                .scale(ZoneMeshMath.sanitizeFinite(scale.x()),
                       ZoneMeshMath.sanitizeFinite(scale.y()),
                       ZoneMeshMath.sanitizeFinite(scale.z()));

        float[] result = new float[16];
        mat.get(result);
        return result;
    }

    /**
     * Checks if the object has negative determinant (requiring winding flip).
     */
    public boolean isMirrored() {
        float[] model = toModelMatrix();
        org.joml.Matrix4f mat = new org.joml.Matrix4f().set(model);
        return mat.determinant() < 0.0f;
    }
}
