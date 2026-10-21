package com.ffxi.model;

public record TextureResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        int payloadSize,
        int textureType,
        String textureName,
        int headerValue,
        int width,
        int height,
        int bitCount,
        String dxtType
) implements ResourceNode {
    public TextureResource {
        textureName = textureName != null ? textureName : "";
        dxtType = dxtType != null ? dxtType : "";
    }
}
