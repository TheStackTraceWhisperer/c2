package com.ffxi.viewer.renderer;

import com.ffxi.export.TextureDecoder;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
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
import static org.lwjgl.opengl.GL30C.GL_RGBA8;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;

/**
 * Shared GL texture atlas: decodes {@link CharacterTextureLoader.TextureSource}s once at construction,
 * uploads them as 2D textures keyed by a normalized (lower-case, trailing-underscore-stripped) name and
 * resolves names at draw time via O(1) cached lookups. Raw DAT byte arrays are <b>not</b> retained after
 * upload, so the atlas does not pin DAT heap memory.
 */
public abstract class GlTextureAtlas implements AutoCloseable {
    private final Map<String, Integer> texturesByName = new HashMap<>();
    /** Memoized results (raw requested name -> texture id) so per-frame lookups never re-normalize or scan. */
    private final Map<String, Integer> lookupCache = new HashMap<>();
    private final int defaultTextureId;
    private boolean closed;

    protected GlTextureAtlas(Map<String, CharacterTextureLoader.TextureSource> textureSources, int wrapMode) {
        ByteBuffer defaultPixel = BufferUtils.createByteBuffer(4);
        defaultPixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
        this.defaultTextureId = createTexture(defaultPixel, 1, 1, wrapMode);

        for (Map.Entry<String, CharacterTextureLoader.TextureSource> entry : textureSources.entrySet()) {
            String normalized = normalizeName(entry.getKey());
            if (normalized.isBlank() || texturesByName.containsKey(normalized)) {
                continue;
            }
            CharacterTextureLoader.TextureSource source = entry.getValue();
            byte[] rgba = TextureDecoder.decode(source.rawData(), source.resource());
            if (rgba == null) {
                texturesByName.put(normalized, defaultTextureId);
                continue;
            }
            ByteBuffer bytes = BufferUtils.createByteBuffer(rgba.length);
            bytes.put(rgba).flip();
            texturesByName.put(normalized, createTexture(bytes, source.resource().width(), source.resource().height(), wrapMode));
        }
    }

    public int textureId(String textureName) {
        if (textureName == null || textureName.isBlank()) {
            return defaultTextureId;
        }
        Integer cached = lookupCache.get(textureName);
        if (cached != null) {
            return cached;
        }
        int resolved = resolve(normalizeName(textureName));
        lookupCache.put(textureName, resolved);
        return resolved;
    }

    private int resolve(String norm) {
        Integer id = texturesByName.get(norm);
        if (id != null) {
            return id;
        }
        for (Map.Entry<String, Integer> e : texturesByName.entrySet()) {
            String key = e.getKey();
            if (key.endsWith(" " + norm) || key.startsWith(norm + " ") || key.contains(norm)) {
                return e.getValue();
            }
        }
        return defaultTextureId;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Set<Integer> uniqueTextureIds = new HashSet<>(texturesByName.values());
        uniqueTextureIds.add(defaultTextureId);
        for (Integer textureId : uniqueTextureIds) {
            glDeleteTextures(textureId);
        }
        texturesByName.clear();
        lookupCache.clear();
    }

    /** Trims, strips trailing underscores and lower-cases (no regex; safe for hot paths). */
    public static String normalizeName(String textureName) {
        if (textureName == null) {
            return "";
        }
        String trimmed = textureName.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '_') {
            end--;
        }
        return trimmed.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }

    private static int createTexture(ByteBuffer rgba, int width, int height, int wrapMode) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }
}
