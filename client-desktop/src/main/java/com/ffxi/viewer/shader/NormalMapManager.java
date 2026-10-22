package com.ffxi.viewer.shader;

import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Manages tangent-space normal maps for FFXI map geometry.
 * Generates high-quality surface normal textures from diffuse texture height/luminance
 * using a Sobel filter (matching retail FFXI DAT Section 0x5D bump-map specifications),
 * and caches generated normal map textures in GPU memory.
 */
public final class NormalMapManager implements AutoCloseable {

    private final Map<String, Integer> normalMapCache = new HashMap<>();
    private final int flatNormalTextureId;

    public NormalMapManager() {
        // Create 1x1 flat tangent-space normal map (0, 0, 1) -> RGB(128, 128, 255)
        flatNormalTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, flatNormalTextureId);
        ByteBuffer flatBuf = BufferUtils.createByteBuffer(4);
        flatBuf.put((byte) 128).put((byte) 128).put((byte) 255).put((byte) 255);
        flatBuf.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, flatBuf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Retrieves or generates a tangent-space normal map for the specified texture.
     */
    public int getOrGenerateNormalMap(String textureName, CharacterTextureLoader.TextureSource source, float bumpStrength) {
        if (textureName == null || source == null) {
            return flatNormalTextureId;
        }

        String key = textureName.toLowerCase();
        if (normalMapCache.containsKey(key)) {
            return normalMapCache.get(key);
        }

        byte[] rawData = source.rawData();
        var res = source.resource();
        int width = res.width();
        int height = res.height();

        if (width <= 0 || height <= 0 || rawData == null || rawData.length < width * height * 4) {
            return flatNormalTextureId;
        }

        int texId = generateNormalMapFromRgba(rawData, width, height, bumpStrength);
        normalMapCache.put(key, texId);
        return texId;
    }

    /**
     * Converts an RGBA diffuse texture into a tangent-space normal map via Sobel height gradient filter.
     */
    public static int generateNormalMapFromRgba(byte[] rgba, int width, int height, float strength) {
        float effectiveStrength = Math.max(0.1f, strength);

        // Precompute luminance grid
        float[] lum = new float[width * height];
        for (int i = 0; i < width * height; i++) {
            int r = rgba[i * 4] & 0xFF;
            int g = rgba[i * 4 + 1] & 0xFF;
            int b = rgba[i * 4 + 2] & 0xFF;
            // Perceptual luminance
            lum[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
        }

        ByteBuffer normalBuffer = BufferUtils.createByteBuffer(width * height * 3);

        for (int y = 0; y < height; y++) {
            int yUp = (y == 0) ? height - 1 : y - 1;
            int yDown = (y == height - 1) ? 0 : y + 1;

            for (int x = 0; x < width; x++) {
                int xLeft = (x == 0) ? width - 1 : x - 1;
                int xRight = (x == width - 1) ? 0 : x + 1;

                float upLeft = lum[yUp * width + xLeft];
                float upCenter = lum[yUp * width + x];
                float upRight = lum[yUp * width + xRight];

                float midLeft = lum[y * width + xLeft];
                float midRight = lum[y * width + xRight];

                float downLeft = lum[yDown * width + xLeft];
                float downCenter = lum[yDown * width + x];
                float downRight = lum[yDown * width + xRight];

                // Sobel kernels
                float dX = (upRight + 2.0f * midRight + downRight) - (upLeft + 2.0f * midLeft + downLeft);
                float dY = (downLeft + 2.0f * downCenter + downRight) - (upLeft + 2.0f * upCenter + upRight);
                float dZ = 1.0f / effectiveStrength;

                // Normalize normal vector
                float len = (float) Math.sqrt(dX * dX + dY * dY + dZ * dZ);
                float nx = (len > 1e-6f) ? dX / len : 0.0f;
                float ny = (len > 1e-6f) ? dY / len : 0.0f;
                float nz = (len > 1e-6f) ? dZ / len : 1.0f;

                byte rByte = (byte) Math.round((nx * 0.5f + 0.5f) * 255.0f);
                byte gByte = (byte) Math.round((ny * 0.5f + 0.5f) * 255.0f);
                byte bByte = (byte) Math.round((nz * 0.5f + 0.5f) * 255.0f);

                normalBuffer.put(rByte).put(gByte).put(bByte);
            }
        }
        normalBuffer.flip();

        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, normalBuffer);
        glGenerateMipmap(GL_TEXTURE_2D);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glBindTexture(GL_TEXTURE_2D, 0);
        return texId;
    }

    public int getFlatNormalTextureId() {
        return flatNormalTextureId;
    }

    public void clear() {
        for (int texId : normalMapCache.values()) {
            if (texId != 0 && texId != flatNormalTextureId) {
                glDeleteTextures(texId);
            }
        }
        normalMapCache.clear();
    }

    @Override
    public void close() {
        clear();
        if (flatNormalTextureId != 0) {
            glDeleteTextures(flatNormalTextureId);
        }
    }
}
