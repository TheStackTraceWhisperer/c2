package com.example.asset.skeleton;

import com.example.asset.geometry.Matrix4f;
import com.example.asset.geometry.Quaternion;
import com.example.asset.geometry.Vector3f;

/**
 * A single bone or joint in a character/creature skeletal hierarchy.
 */
public record Joint(
        int index,
        int parentIndex,
        Quaternion rotation,
        Vector3f translation
) {
    public Matrix4f toLocalTransform() {
        Matrix4f rot = rotation.toMatrix4f();
        Matrix4f trans = Matrix4f.translation(translation.x(), translation.y(), translation.z());
        return trans.multiply(rot);
    }
}
