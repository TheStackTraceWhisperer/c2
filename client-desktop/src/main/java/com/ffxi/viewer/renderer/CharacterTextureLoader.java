package com.ffxi.viewer.renderer;

import com.ffxi.export.TextureDecoder;
import com.ffxi.model.TextureResource;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30C.GL_RGBA8;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;

public final class CharacterTextureLoader implements AutoCloseable {
    private final Map<String, Integer> texturesByName = new HashMap<>();
    private final int defaultTextureId;

    public CharacterTextureLoader(Map<String, TextureSource> textureSources) {
        ByteBuffer defaultPixel = BufferUtils.createByteBuffer(4);
        defaultPixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
        this.defaultTextureId = createTexture(defaultPixel, 1, 1);

        for (Map.Entry<String, TextureSource> entry : textureSources.entrySet()) {
            String normalized = normalizeName(entry.getKey());
            if (normalized.isBlank() || texturesByName.containsKey(normalized)) {
                continue;
            }

            TextureSource source = entry.getValue();
            byte[] rgba = TextureDecoder.decode(source.rawData(), source.resource());
            if (rgba == null) {
                texturesByName.put(normalized, defaultTextureId);
                continue;
            }

            ByteBuffer bytes = BufferUtils.createByteBuffer(rgba.length);
            bytes.put(rgba).flip();
            int textureId = createTexture(bytes, source.resource().width(), source.resource().height());
            texturesByName.put(normalized, textureId);
        }
    }

    public int textureId(String textureName) {
        if (textureName == null || textureName.isBlank()) {
            return defaultTextureId;
        }
        return texturesByName.getOrDefault(normalizeName(textureName), defaultTextureId);
    }

    @Override
    public void close() {
        Set<Integer> uniqueTextureIds = new HashSet<>(texturesByName.values());
        uniqueTextureIds.add(defaultTextureId);
        for (Integer textureId : uniqueTextureIds) {
            glDeleteTextures(textureId);
        }
    }

    public static String normalizeName(String textureName) {
        if (textureName == null) {
            return "";
        }
        String normalized = textureName.trim().replaceAll("_+$", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static int createTexture(ByteBuffer rgba, int width, int height) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    public record TextureSource(TextureResource resource, byte[] rawData) {
    }
}
