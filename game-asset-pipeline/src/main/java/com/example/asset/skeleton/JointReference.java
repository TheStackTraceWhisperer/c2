package com.example.asset.skeleton;

import com.example.asset.geometry.Vector3f;

/**
 * Standard reference point attached to a joint (e.g. above head, hand, foot).
 */
public record JointReference(
        int jointIndex,
        Vector3f unkV0,
        Vector3f offset
) {
}
