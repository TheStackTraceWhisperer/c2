package com.ffxi.viewer.renderer;

import com.ffxi.model.TextureResource;

import java.util.Map;

import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;

/** Texture atlas for character/equipment textures (edge-clamped). */
public final class CharacterTextureLoader extends GlTextureAtlas {

    public CharacterTextureLoader(Map<String, TextureSource> textureSources) {
        super(textureSources, GL_CLAMP_TO_EDGE);
    }

    public static String normalizeName(String textureName) {
        return GlTextureAtlas.normalizeName(textureName);
    }

    public record TextureSource(TextureResource resource, byte[] rawData) {
    }
}
