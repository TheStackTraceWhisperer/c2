package com.example.asset.texture;

/**
 * An uncompressed 32-bit RGBA (8:8:8:8) in-memory texture decoded from a game asset section.
 */
public record DecodedTexture(
        String name,
        int width,
        int height,
        String format,
        byte[] rgbaPixels
) {

    public int pixelCount() {
        return width * height;
    }

    public int byteLength() {
        return rgbaPixels.length;
    }
}
