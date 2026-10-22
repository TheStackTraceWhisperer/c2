package com.example.asset.texture;

import com.example.asset.reader.DatByteReader;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Parses FFXI S20_TEXTURE sections and decompresses image data to 32-bit RGBA.
 */
public final class TextureParser {

    private static final int HEADER_OFFSET_TYPE = 0x00;
    private static final int HEADER_OFFSET_NAME = 0x01; // 16 bytes
    private static final int HEADER_OFFSET_WIDTH = 0x15;
    private static final int HEADER_OFFSET_HEIGHT = 0x19;
    private static final int HEADER_OFFSET_BITCOUNT = 0x1F;
    private static final int HEADER_OFFSET_FOURCC = 0x39;
    private static final int DXT_DATA_START = 0x45; // 69 bytes (0x39 + 4 + 4 + 4)
    private static final int PALETTED_91_DATA_START = 0x39;
    private static final int PALETTED_B1_DATA_START = 0x3D;

    private TextureParser() {
    }

    /**
     * Parses a raw S20_TEXTURE payload slice and returns a DecodedTexture.
     */
    public static Optional<DecodedTexture> parseTexture(DatByteReader payload) {
        if (payload.remaining() < 0x39) {
            return Optional.empty();
        }

        // 1. Read Type and Texture Name
        payload.position(HEADER_OFFSET_TYPE);
        int textureType = payload.nextU8();

        payload.position(HEADER_OFFSET_NAME);
        String rawName = payload.nextString(16).trim();
        String textureName = rawName.replaceAll("[\0\r\n]", "").trim();
        if (textureName.isEmpty()) {
            return Optional.empty();
        }

        // 2. Read Dimensions
        payload.position(HEADER_OFFSET_WIDTH);
        int width = payload.nextI32();
        payload.position(HEADER_OFFSET_HEIGHT);
        int height = payload.nextI32();

        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            return Optional.empty();
        }

        payload.position(HEADER_OFFSET_BITCOUNT);
        int bitCount = payload.nextU16();

        byte[] rgbaPixels;
        String format;

        if (textureType == 0xA1) {
            // DXT Compressed Texture
            payload.position(HEADER_OFFSET_FOURCC);
            byte[] fourCcBytes = payload.nextBytes(4);
            String fourCc = new String(fourCcBytes, StandardCharsets.US_ASCII);

            if (payload.remaining() < (DXT_DATA_START - payload.position())) {
                return Optional.empty();
            }

            payload.position(DXT_DATA_START);
            byte[] compressedData = payload.nextBytes(payload.remaining());

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
                return Optional.empty();
            }
        } else if (textureType == 0x91 || textureType == 0xB1) {
            int dataStart = (textureType == 0xB1) ? PALETTED_B1_DATA_START : PALETTED_91_DATA_START;
            if (payload.remaining() < (dataStart - payload.position())) {
                return Optional.empty();
            }
            payload.position(dataStart);
            byte[] rawData = payload.nextBytes(payload.remaining());

            if (bitCount == 32) {
                format = "RGBA32";
                rgbaPixels = decodeDirect32(rawData, width, height);
            } else {
                format = "PALETTED_8BIT";
                rgbaPixels = decodePaletted(rawData, width, height);
            }
        } else {
            return Optional.empty();
        }

        return Optional.of(new DecodedTexture(textureName, width, height, format, rgbaPixels));
    }

    private static byte[] decodeDirect32(byte[] data, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        int pixelOffset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixelOffset + 4 > data.length) break;
                int b = data[pixelOffset++] & 0xFF;
                int g = data[pixelOffset++] & 0xFF;
                int r = data[pixelOffset++] & 0xFF;
                int a = data[pixelOffset++] & 0xFF;

                // Vertical flip (FFXI bitmap convention)
                int outIdx = (width * (height - (y + 1)) + x) * 4;
                rgba[outIdx] = (byte) r;
                rgba[outIdx + 1] = (byte) g;
                rgba[outIdx + 2] = (byte) b;
                rgba[outIdx + 3] = (byte) a;
            }
        }
        return rgba;
    }

    private static byte[] decodePaletted(byte[] data, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        if (data.length < 1024) {
            return rgba;
        }

        // First 1024 bytes (256 * 4) palette (BGRA)
        int[] paletteR = new int[256];
        int[] paletteG = new int[256];
        int[] paletteB = new int[256];
        int[] paletteA = new int[256];

        boolean anyNonZeroAlpha = false;
        for (int i = 0; i < 256; i++) {
            paletteB[i] = data[i * 4] & 0xFF;
            paletteG[i] = data[i * 4 + 1] & 0xFF;
            paletteR[i] = data[i * 4 + 2] & 0xFF;
            paletteA[i] = data[i * 4 + 3] & 0xFF;
            if (paletteA[i] > 0) {
                anyNonZeroAlpha = true;
            }
        }
        if (!anyNonZeroAlpha) {
            java.util.Arrays.fill(paletteA, 255);
        }

        int pixelOffset = 1024;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixelOffset >= data.length) break;
                int idx = data[pixelOffset++] & 0xFF;

                // Vertical flip (matching xim / FFXI DirectX surface convention)
                int outOffset = (width * (height - (y + 1)) + x) * 4;
                rgba[outOffset] = (byte) paletteR[idx];
                rgba[outOffset + 1] = (byte) paletteG[idx];
                rgba[outOffset + 2] = (byte) paletteB[idx];
                rgba[outOffset + 3] = (byte) paletteA[idx];
            }
        }
        return rgba;
    }
}
