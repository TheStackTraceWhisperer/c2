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

        try {
            ByteReader reader = new ByteReader(data, section.sectionId());
            reader.setPosition(dataStartPosition);
            advanceTextureHeader(reader);

            switch (section.textureType()) {
                case TYPE_PALETTE, TYPE_PALETTE_ALT -> {
                    boolean alt = section.textureType() == TYPE_PALETTE_ALT;
                    int prefix = alt ? 4 : 0;
                    if (section.bitCount() == 32) {
                        if (!hasBytes(reader, (long) prefix + rgbaLength)) {
                            return null;
                        }
                        if (alt) {
                            reader.next32();
                        }
                        return decodeDirectBgra(reader, width, height);
                    }
                    if (!hasBytes(reader, prefix + PALETTE_SIZE * 4L + (long) width * height)) {
                        return null;
                    }
                    if (alt) {
                        reader.next32();
                    }
                    return decodePalette(reader, width, height);
                }
                case TYPE_DXT -> {
                    advanceDxtHeader(reader);
                    String dxt = section.dxtType();
                    int blockBytes = switch (dxt) {
                        case "1TXD" -> 8;
                        case "3TXD", "5TXD" -> 16;
                        default -> 0;
                    };
                    if (blockBytes == 0) {
                        return null;
                    }
                    long blocks = ((width + 3L) / 4) * ((height + 3L) / 4);
                    if (!hasBytes(reader, blocks * blockBytes)) {
                        return null;
                    }
                    int off = reader.getPosition();
                    return switch (dxt) {
                        case "1TXD" -> decodeDxt1(data, off, width, height);
                        case "3TXD" -> decodeDxt3(data, off, width, height);
                        default -> decodeDxt5(data, off, width, height);
                    };
                }
                default -> {
                    return null;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasBytes(ByteReader reader, long needed) {
        return needed >= 0 && reader.remaining() >= needed;
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

    private static byte[] decodePalette(ByteReader reader, int width, int height) {
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
            int destY = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int[] color = palette[reader.next8()];
                writePixel(rgba, width, x, destY, color[0], color[1], color[2], color[3]);
            }
        }
        return rgba;
    }

    private static byte[] decodeDirectBgra(ByteReader reader, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            int destY = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int bgra = reader.next32();
                writePixel(rgba, width, x, destY,
                        (bgra >>> 16) & 0xFF, (bgra >>> 8) & 0xFF, bgra & 0xFF, (bgra >>> 24) & 0xFF);
            }
        }
        return rgba;
    }

    /**
     * Decodes DXT1 (BC1) blocks starting at {@code off} into a top-left-origin RGBA array.
     * Blocks are not flipped vertically. Missing source bytes are treated as an error
     * ({@link IllegalArgumentException}).
     */
    public static byte[] decodeDxt1(byte[] src, int off, int width, int height) {
        return decodeBlocks(src, off, width, height, 8, false, 0);
    }

    /** Decodes DXT3 (BC2): 8 bytes explicit 4-bit alpha (little-endian, row-major) + colour block. */
    public static byte[] decodeDxt3(byte[] src, int off, int width, int height) {
        return decodeBlocks(src, off, width, height, 16, true, 3);
    }

    /** Decodes DXT5 (BC3): interpolated alpha block + colour block (4-colour mode). */
    public static byte[] decodeDxt5(byte[] src, int off, int width, int height) {
        return decodeBlocks(src, off, width, height, 16, true, 5);
    }

    private static byte[] decodeBlocks(byte[] src, int off, int width, int height,
                                       int blockBytes, boolean alphaBlock, int alphaMode) {
        if (src == null || width <= 0 || height <= 0 || off < 0) {
            throw new IllegalArgumentException("invalid DXT arguments");
        }
        long blocksX = (width + 3L) / 4;
        long blocksY = (height + 3L) / 4;
        long rgbaLength = (long) width * height * 4L;
        if (rgbaLength > Integer.MAX_VALUE || off + blocksX * blocksY * blockBytes > src.length) {
            throw new IllegalArgumentException("DXT source too short");
        }
        byte[] rgba = new byte[(int) rgbaLength];
        int pos = off;
        int[] r = new int[4];
        int[] g = new int[4];
        int[] b = new int[4];
        int[] a = new int[16];
        for (int blockY = 0; blockY < height; blockY += 4) {
            for (int blockX = 0; blockX < width; blockX += 4) {
                java.util.Arrays.fill(a, 255);
                if (alphaBlock) {
                    if (alphaMode == 3) {
                        long bits = readLe64(src, pos);
                        for (int i = 0; i < 16; i++) {
                            int nibble = (int) ((bits >>> (4 * i)) & 0xF);
                            a[i] = nibble * 255 / 15;
                        }
                    } else {
                        decodeBc3Alpha(src, pos, a);
                    }
                    pos += 8;
                }
                int color0 = (src[pos] & 0xFF) | ((src[pos + 1] & 0xFF) << 8);
                int color1 = (src[pos + 2] & 0xFF) | ((src[pos + 3] & 0xFF) << 8);
                int indices = (src[pos + 4] & 0xFF) | ((src[pos + 5] & 0xFF) << 8)
                        | ((src[pos + 6] & 0xFF) << 16) | ((src[pos + 7] & 0xFF) << 24);
                pos += 8;

                decodeRgb565(color0, r, g, b, 0);
                decodeRgb565(color1, r, g, b, 1);
                boolean fourColor = alphaBlock || color0 > color1;
                boolean transparent3 = false;
                if (fourColor) {
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
                    transparent3 = true;
                }

                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        int x = blockX + col;
                        int y = blockY + row;
                        if (x >= width || y >= height) {
                            continue;
                        }
                        int pi = row * 4 + col;
                        int idx = (indices >>> (2 * pi)) & 0x03;
                        int alpha = (transparent3 && idx == 3) ? 0 : a[pi];
                        writePixel(rgba, width, x, y, r[idx], g[idx], b[idx], alpha);
                    }
                }
            }
        }
        return rgba;
    }

    private static long readLe64(byte[] src, int pos) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (src[pos + i] & 0xFFL);
        }
        return v;
    }

    private static void decodeBc3Alpha(byte[] src, int pos, int[] out) {
        int a0 = src[pos] & 0xFF;
        int a1 = src[pos + 1] & 0xFF;
        int[] pal = new int[8];
        pal[0] = a0;
        pal[1] = a1;
        if (a0 > a1) {
            for (int i = 1; i <= 6; i++) {
                pal[i + 1] = ((7 - i) * a0 + i * a1) / 7;
            }
        } else {
            for (int i = 1; i <= 4; i++) {
                pal[i + 1] = ((5 - i) * a0 + i * a1) / 5;
            }
            pal[6] = 0;
            pal[7] = 255;
        }
        long bits = 0;
        for (int i = 5; i >= 0; i--) {
            bits = (bits << 8) | (src[pos + 2 + i] & 0xFFL);
        }
        for (int i = 0; i < 16; i++) {
            out[i] = pal[(int) ((bits >>> (3 * i)) & 0x7)];
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
