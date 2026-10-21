package com.ffxi.model;

import java.util.List;

public record WeightedMeshResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int payloadSize,
        int unknownHeaderValue,
        int meshConfig,
        boolean alphaDiscardEnabled,
        int weightedSectionCount,
        int positionCount,
        int normalCount,
        int primitiveCount,
        int indexOffset,
        int colorOffset,
        int uvOffset,
        String textureName,
        int offsetExtension,
        boolean zoneColorScaled,
        List<WeightedMeshChunk> chunks,
        List<BgraColor> colors,
        List<Vec2f> texCoords,
        List<Integer> positionIndices,
        List<Integer> normalIndices
) implements ResourceNode {
    public WeightedMeshResource {
        textureName = textureName != null ? textureName : "";
        chunks = List.copyOf(chunks != null ? chunks : List.of());
        colors = List.copyOf(colors != null ? colors : List.of());
        texCoords = List.copyOf(texCoords != null ? texCoords : List.of());
        positionIndices = List.copyOf(positionIndices != null ? positionIndices : List.of());
        normalIndices = List.copyOf(normalIndices != null ? normalIndices : List.of());
    }

    public WeightedMeshResource(
            String sectionId,
            String sectionType,
            int sectionStart,
            int sectionSize,
            int payloadSize,
            int unknownHeaderValue,
            int meshConfig,
            boolean alphaDiscardEnabled,
            int weightedSectionCount,
            int positionCount,
            int normalCount,
            int primitiveCount,
            int indexOffset,
            int colorOffset,
            int uvOffset,
            String textureName
    ) {
        this(sectionId, sectionType, sectionStart, sectionSize, payloadSize, unknownHeaderValue, meshConfig,
                alphaDiscardEnabled, weightedSectionCount, positionCount, normalCount, primitiveCount, indexOffset,
                colorOffset, uvOffset, textureName, 0, false, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
