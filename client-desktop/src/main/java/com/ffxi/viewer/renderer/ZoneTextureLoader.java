package com.ffxi.viewer.renderer;

import java.util.Map;

import static org.lwjgl.opengl.GL11C.GL_REPEAT;

/** Texture atlas for zone textures (repeat-wrapped). Raw DAT bytes are dropped after GL upload. */
public final class ZoneTextureLoader extends GlTextureAtlas {

    public ZoneTextureLoader(Map<String, CharacterTextureLoader.TextureSource> textureSources) {
        super(textureSources, GL_REPEAT);
    }

    public static String normalizeName(String textureName) {
        return GlTextureAtlas.normalizeName(textureName);
    }
}
