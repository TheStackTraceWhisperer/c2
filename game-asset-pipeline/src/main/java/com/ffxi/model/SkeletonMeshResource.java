package com.ffxi.model;

import java.util.List;

public record SkeletonMeshResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int payloadSize,
        int flags1,
        int flags2,
        int flags3,
        int flags4,
        int flags5,
        int flags6,
        boolean clothEffect,
        boolean useJointArray,
        boolean symmetric,
        boolean hasNormals,
        int occludeType,
        int instructionOffset,
        int meshCountHint,
        int instructionCountHint,
        int jointArrayOffset,
        int jointCount,
        int vertexCountsOffset,
        int vertexCountsEntryCount,
        int vertexJointMappingOffset,
        int vertexJointMappingCount,
        int vertexDataOffset,
        int vertexDataSizeHint,
        int endOffset,
        int endOffsetDataSizeHint,
        SkeletonMeshClothProperties clothProperties,
        List<Integer> joints,
        int singleJointVertexCount,
        int doubleJointVertexCount,
        List<SkeletonMeshVertex> vertices,
        List<SkeletonMeshMesh> meshes
) implements ResourceNode {
    public SkeletonMeshResource {
        joints = List.copyOf(joints != null ? joints : List.of());
        vertices = List.copyOf(vertices != null ? vertices : List.of());
        meshes = List.copyOf(meshes != null ? meshes : List.of());
    }

    public SkeletonMeshResource(
            String sectionId,
            String sectionType,
            int sectionStart,
            int sectionSize,
            int payloadSize,
            int flags1,
            int flags2,
            int flags3,
            int flags4,
            int flags5,
            int flags6,
            boolean clothEffect,
            boolean useJointArray,
            boolean symmetric,
            boolean hasNormals,
            int occludeType,
            int instructionOffset,
            int meshCountHint,
            int instructionCountHint,
            int jointArrayOffset,
            int jointCount,
            int vertexCountsOffset,
            int vertexCountsEntryCount,
            int vertexJointMappingOffset,
            int vertexJointMappingCount,
            int vertexDataOffset,
            int vertexDataSizeHint,
            int endOffset,
            int endOffsetDataSizeHint
    ) {
        this(sectionId, sectionType, sectionStart, sectionSize, payloadSize, flags1, flags2, flags3, flags4, flags5,
                flags6, clothEffect, useJointArray, symmetric, hasNormals, occludeType, instructionOffset,
                meshCountHint, instructionCountHint, jointArrayOffset, jointCount, vertexCountsOffset,
                vertexCountsEntryCount, vertexJointMappingOffset, vertexJointMappingCount, vertexDataOffset,
                vertexDataSizeHint, endOffset, endOffsetDataSizeHint, null, List.of(), 0, 0, List.of(), List.of());
    }
}
