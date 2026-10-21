package com.ffxi.model;

import java.util.List;

public record SkeletonResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int payloadSize,
        int unknownHeaderValue,
        int jointCount,
        int jointRecordBytes,
        int referenceCount,
        int referenceHeaderValue,
        int boundingBoxCount,
        List<SkeletonJoint> joints,
        List<SkeletonJointReference> jointReferences,
        List<SkeletonBoundingBox> boundingBoxes
) implements ResourceNode {
    public SkeletonResource {
        joints = List.copyOf(joints != null ? joints : List.of());
        jointReferences = List.copyOf(jointReferences != null ? jointReferences : List.of());
        boundingBoxes = List.copyOf(boundingBoxes != null ? boundingBoxes : List.of());
    }

    public SkeletonResource(
            String sectionId,
            String sectionType,
            int sectionStart,
            int sectionSize,
            int payloadSize,
            int unknownHeaderValue,
            int jointCount,
            int jointRecordBytes,
            int referenceCount,
            int referenceHeaderValue,
            int boundingBoxCount
    ) {
        this(sectionId, sectionType, sectionStart, sectionSize, payloadSize, unknownHeaderValue, jointCount,
                jointRecordBytes, referenceCount, referenceHeaderValue, boundingBoxCount, List.of(), List.of(),
                List.of());
    }
}
