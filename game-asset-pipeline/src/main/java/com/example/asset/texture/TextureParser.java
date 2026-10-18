package com.example.asset.texture;

import com.example.asset.reader.DatByteReader;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Parses FFXI S20_TEXTURE sections and decompresses image data to 32-bit RGBA.
 */
public final class TextureParser {

    private static final int HEADER_OFFSET_NAME = 0;
    private static final int HEADER_OFFSET_WIDTH = 0x15;
    private static final int HEADER_OFFSET_HEIGHT = 0x19;
    private static final int HEADER_OFFSET_FOURCC = 0x39;
    private static final int HEADER_DATA_START = 0x50; // 80 bytes

    private TextureParser() {
    }

    /**
     * Parses a raw S20_TEXTURE payload slice and returns a DecodedTexture.
     */
    public static Optional<DecodedTexture> parseTexture(DatByteReader payload) {
        if (payload.remaining() < HEADER_DATA_START) {
            return Optional.empty();
        }

        // 1. Read Texture Name (First 16 bytes)
        payload.position(HEADER_OFFSET_NAME);
        String rawName = payload.nextString(16).trim();
        String[] parts = rawName.split("\\s+");
        String textureName = parts[parts.length - 1].replaceAll("[^a-zA-Z0-9_-]", "").trim();
        if (textureName.isEmpty()) {
            textureName = rawName.replaceAll("[^a-zA-Z0-9_-]", "").trim();
        }

        // 2. Read Dimensions
        payload.position(HEADER_OFFSET_WIDTH);
        int width = payload.nextI32();
        payload.position(HEADER_OFFSET_HEIGHT);
        int height = payload.nextI32();

        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            return Optional.empty();
        }

        // 3. Read Compression FourCC
        payload.position(HEADER_OFFSET_FOURCC);
        byte[] fourCcBytes = payload.nextBytes(4);
        String fourCc = new String(fourCcBytes, StandardCharsets.US_ASCII);

        payload.position(HEADER_DATA_START);
        byte[] compressedData = payload.nextBytes(payload.remaining());

        byte[] rgbaPixels;
        String format;

        if (fourCc.contains("DXT3") || fourCc.contains("3TXD")) {
            rgbaPixels = DxtDecompressor.decompressDxt3(compressedData, width, height);
            format = "DXT3";
        } else if (fourCc.contains("DXT5") || fourCc.contains("5TXD")) {
            rgbaPixels = DxtDecompressor.decompressDxt5(compressedData, width, height);
            format = "DXT5";
        } else if (fourCc.contains("DXT1") || fourCc.contains("1TXD")) {
            rgbaPixels = DxtDecompressor.decompressDxt1(compressedData, width, height);
            format = "DXT1";
        } else {
            // Uncompressed or 8-bit paletted fallback
            format = "PALETTED_8BIT";
            rgbaPixels = decodePaletted(compressedData, width, height);
        }

        return Optional.of(new DecodedTexture(textureName, width, height, format, rgbaPixels));
    }

    private static byte[] decodePaletted(byte[] data, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        if (data.length < 1024) {
            return rgba;
        }

        // First 1024 bytes (256 * 4) palette (BGRA)
        int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            int b = data[i * 4] & 0xFF;
            int g = data[i * 4 + 1] & 0xFF;
            int r = data[i * 4 + 2] & 0xFF;
            int a = data[i * 4 + 3] & 0xFF;
            palette[i] = (r << 24) | (g << 16) | (b << 8) | a;
        }

        int pixelOffset = 1024;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixelOffset >= data.length) break;
                int idx = data[pixelOffset++] & 0xFF;
                int color = palette[idx];
                int outOffset = (y * width + x) * 4;
                rgba[outOffset] = (byte) ((color >>> 24) & 0xFF);
                rgba[outOffset + 1] = (byte) ((color >>> 16) & 0xFF);
                rgba[outOffset + 2] = (byte) ((color >>> 8) & 0xFF);
                rgba[outOffset + 3] = (byte) (color & 0xFF);
            }
        }
        return rgba;
    }
}
