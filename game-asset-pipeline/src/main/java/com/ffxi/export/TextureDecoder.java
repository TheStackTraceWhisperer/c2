package com.ffxi.export;

import com.ffxi.io.ByteReader;
import com.ffxi.model.TextureResource;

public final class TextureDecoder {
    private static final int TYPE_PALETTE = 0x91;
    private static final int TYPE_DXT = 0xA1;
    private static final int TYPE_PALETTE_ALT = 0xB1;
    private static final int PALETTE_SIZE = 256;

    private TextureDecoder() {
    }

    /**
     * Decodes a raw S20 texture section payload to a top-down RGBA byte array.
     * Follows XIM's TextureSection / PaletteParser / TextureHelper layout exactly.
     *
     * @param data    full DAT byte array
     * @param section parsed TextureResource (provides dataStartPosition = sectionStart + 0x10,
     *                textureType, width, height, bitCount, dxtType)
     * @return RGBA byte[] of length width*height*4, or null if unsupported type
     */
    public static byte[] decode(byte[] data, TextureResource section) {
        if (data == null || section == null) {
            return null;
        }

        int width = section.width();
        int height = section.height();
        if (width <= 0 || height <= 0) {
            return null;
        }

        long rgbaLength = (long) width * height * 4L;
        if (rgbaLength > Integer.MAX_VALUE) {
            return null;
        }

        int dataStartPosition = section.sectionStart() + 0x10;
        if (dataStartPosition < 0 || dataStartPosition >= data.length) {
            return null;
        }

        ByteReader reader = new ByteReader(data, section.sectionId());
        reader.setPosition(dataStartPosition);
        advanceTextureHeader(reader);

        return switch (section.textureType()) {
            case TYPE_PALETTE, TYPE_PALETTE_ALT -> section.bitCount() == 32
                    ? decodeDirectBgra(reader, width, height)
                    : decodePalette(reader, width, height, section.textureType() == TYPE_PALETTE_ALT);
            case TYPE_DXT -> {
                advanceDxtHeader(reader);
                yield switch (section.dxtType()) {
                    case "1TXD" -> decodeDxt1(reader, width, height);
                    case "3TXD" -> decodeDxt3(reader, width, height);
                    default -> null;
                };
            }
            default -> null;
        };
    }

    private static void advanceTextureHeader(ByteReader reader) {
        reader.next8();
        reader.nextString(0x10);
        reader.next32();
        reader.next32();
        reader.next32();
        reader.next16();
        reader.next16();
        for (int i = 0; i < 5; i++) {
            reader.next32();
        }
        reader.next32();
    }

    private static void advanceDxtHeader(ByteReader reader) {
        reader.nextString(4);
        reader.next32();
        reader.next32();
    }

    private static byte[] decodePalette(ByteReader reader, int width, int height, boolean hasAltPalettePrefix) {
        if (hasAltPalettePrefix) {
            reader.next32();
        }
        int[][] palette = new int[PALETTE_SIZE][4];
        for (int i = 0; i < PALETTE_SIZE; i++) {
            int bgra = reader.next32();
            palette[i][0] = (bgra >>> 16) & 0xFF;
            palette[i][1] = (bgra >>> 8) & 0xFF;
            palette[i][2] = bgra & 0xFF;
            palette[i][3] = (bgra >>> 24) & 0xFF;
        }

        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] color = palette[reader.next8()];
                writePixel(rgba, width, x, y, color[0], color[1], color[2], color[3]);
            }
        }
        return rgba;
    }

    private static byte[] decodeDirectBgra(ByteReader reader, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bgra = reader.next32();
                writePixel(
                        rgba,
                        width,
                        x,
                        y,
                        (bgra >>> 16) & 0xFF,
                        (bgra >>> 8) & 0xFF,
                        bgra & 0xFF,
                        (bgra >>> 24) & 0xFF
                );
            }
        }
        return rgba;
    }

    private static byte[] decodeDxt1(ByteReader reader, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int blockY = 0; blockY < height; blockY += 4) {
            for (int blockX = 0; blockX < width; blockX += 4) {
                int color0 = reader.next16();
                int color1 = reader.next16();

                int[] r = new int[4];
                int[] g = new int[4];
                int[] b = new int[4];
                int[] a = new int[]{255, 255, 255, 255};
                decodeRgb565(color0, r, g, b, 0);
                decodeRgb565(color1, r, g, b, 1);

                if (color0 > color1) {
                    r[2] = ((2 * r[0]) + r[1]) / 3;
                    g[2] = ((2 * g[0]) + g[1]) / 3;
                    b[2] = ((2 * b[0]) + b[1]) / 3;
                    r[3] = (r[0] + (2 * r[1])) / 3;
                    g[3] = (g[0] + (2 * g[1])) / 3;
                    b[3] = (b[0] + (2 * b[1])) / 3;
                } else {
                    r[2] = (r[0] + r[1]) / 2;
                    g[2] = (g[0] + g[1]) / 2;
                    b[2] = (b[0] + b[1]) / 2;
                    r[3] = 0;
                    g[3] = 0;
                    b[3] = 0;
                    a[3] = 0;
                }

                int indices = reader.next32BE();
                writeDxtBlock(rgba, width, height, blockX, blockY, indices, r, g, b, a, null);
            }
        }
        return rgba;
    }

    private static byte[] decodeDxt3(ByteReader reader, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int blockY = 0; blockY < height; blockY += 4) {
            for (int blockX = 0; blockX < width; blockX += 4) {
                long alphaBlock = ((reader.next32() & 0xFFFFFFFFL) << 32) | (reader.next32() & 0xFFFFFFFFL);
                int color0 = reader.next16();
                int color1 = reader.next16();

                int[] r = new int[4];
                int[] g = new int[4];
                int[] b = new int[4];
                decodeRgb565(color0, r, g, b, 0);
                decodeRgb565(color1, r, g, b, 1);
                r[2] = ((2 * r[0]) + r[1]) / 3;
                g[2] = ((2 * g[0]) + g[1]) / 3;
                b[2] = ((2 * b[0]) + b[1]) / 3;
                r[3] = (r[0] + (2 * r[1])) / 3;
                g[3] = (g[0] + (2 * g[1])) / 3;
                b[3] = (b[0] + (2 * b[1])) / 3;

                int indices = reader.next32BE();
                writeDxtBlock(rgba, width, height, blockX, blockY, indices, r, g, b, null, alphaBlock);
            }
        }
        return rgba;
    }

    private static void writeDxtBlock(
            byte[] rgba,
            int width,
            int height,
            int blockX,
            int blockY,
            int indices,
            int[] r,
            int[] g,
            int[] b,
            int[] a,
            Long alphaBlock
    ) {
        for (int y = blockY, count = 15; y < blockY + 4; y++) {
            for (int x = blockX + 3; x >= blockX; x--, count--) {
                int paletteIndex = (indices >>> (count * 2)) & 0x03;
                int alpha = a != null ? a[paletteIndex] : (int) ((((alphaBlock >>> (4 * count)) & 0x0F) * 255L) / 16L);
                if (x < width && y < height) {
                    writePixel(rgba, width, x, y, r[paletteIndex], g[paletteIndex], b[paletteIndex], alpha);
                }
            }
        }
    }

    private static void decodeRgb565(int color, int[] r, int[] g, int[] b, int index) {
        r[index] = ((color >>> 11) & 0x1F) * 255 / 31;
        g[index] = ((color >>> 5) & 0x3F) * 255 / 63;
        b[index] = (color & 0x1F) * 255 / 31;
    }

    private static void writePixel(byte[] rgba, int width, int x, int y, int red, int green, int blue, int alpha) {
        int offset = ((y * width) + x) * 4;
        rgba[offset] = (byte) red;
        rgba[offset + 1] = (byte) green;
        rgba[offset + 2] = (byte) blue;
        rgba[offset + 3] = (byte) alpha;
    }
}
