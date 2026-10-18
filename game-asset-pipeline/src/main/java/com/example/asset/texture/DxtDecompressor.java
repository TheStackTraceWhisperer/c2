package com.example.asset.texture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * High-performance block decompressor for DXT1 (BC1), DXT3 (BC2), and DXT5 (BC3) texture formats.
 * Decodes compressed blocks into 32-bit RGBA (8:8:8:8) pixel buffers.
 */
public final class DxtDecompressor {

    private DxtDecompressor() {
    }

    /**
     * Decompresses DXT1 compressed byte data into a 32-bit RGBA byte array.
     */
    public static byte[] decompressDxt1(byte[] data, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int blocksX = (width + 3) / 4;
        int blocksY = (height + 3) / 4;

        int[] colors = new int[4];

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int c0 = buffer.getShort() & 0xFFFF;
                int c1 = buffer.getShort() & 0xFFFF;

                decodeRgb565(c0, colors, 0);
                decodeRgb565(c1, colors, 1);

                if (c0 > c1) {
                    colors[2] = interpolateRgb(colors[0], colors[1], 2, 1, 3, 255);
                    colors[3] = interpolateRgb(colors[0], colors[1], 1, 2, 3, 255);
                } else {
                    colors[2] = interpolateRgb(colors[0], colors[1], 1, 1, 2, 255);
                    colors[3] = 0; // Transparent
                }

                int bits = buffer.getInt();
                writeBlock(rgba, bx * 4, by * 4, width, height, colors, bits, null);
            }
        }
        return rgba;
    }

    /**
     * Decompresses DXT3 compressed byte data (explicit 4-bit alpha + DXT1 color) into RGBA.
     */
    public static byte[] decompressDxt3(byte[] data, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int blocksX = (width + 3) / 4;
        int blocksY = (height + 3) / 4;

        int[] colors = new int[4];
        int[] alphas = new int[16];

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                // 8 bytes of explicit 4-bit alphas (16 pixels)
                long alphaBits = buffer.getLong();
                for (int i = 0; i < 16; i++) {
                    int a4 = (int) ((alphaBits >>> (i * 4)) & 0xF);
                    alphas[i] = (a4 << 4) | a4;
                }

                int c0 = buffer.getShort() & 0xFFFF;
                int c1 = buffer.getShort() & 0xFFFF;

                decodeRgb565(c0, colors, 0);
                decodeRgb565(c1, colors, 1);
                colors[2] = interpolateRgb(colors[0], colors[1], 2, 1, 3, 255);
                colors[3] = interpolateRgb(colors[0], colors[1], 1, 2, 3, 255);

                int bits = buffer.getInt();
                writeBlock(rgba, bx * 4, by * 4, width, height, colors, bits, alphas);
            }
        }
        return rgba;
    }

    /**
     * Decompresses DXT5 compressed byte data (interpolated alpha + DXT1 color) into RGBA.
     */
    public static byte[] decompressDxt5(byte[] data, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int blocksX = (width + 3) / 4;
        int blocksY = (height + 3) / 4;

        int[] colors = new int[4];
        int[] alphaPalette = new int[8];
        int[] alphas = new int[16];

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int a0 = buffer.get() & 0xFF;
                int a1 = buffer.get() & 0xFF;
                alphaPalette[0] = a0;
                alphaPalette[1] = a1;

                if (a0 > a1) {
                    for (int i = 1; i <= 6; i++) {
                        alphaPalette[i + 1] = ((7 - i) * a0 + i * a1) / 7;
                    }
                } else {
                    for (int i = 1; i <= 4; i++) {
                        alphaPalette[i + 1] = ((5 - i) * a0 + i * a1) / 5;
                    }
                    alphaPalette[6] = 0;
                    alphaPalette[7] = 255;
                }

                // 48 bits (6 bytes) of 3-bit alpha indices
                long alphaIndices = 0;
                for (int b = 0; b < 6; b++) {
                    alphaIndices |= ((long) (buffer.get() & 0xFF)) << (b * 8);
                }
                for (int i = 0; i < 16; i++) {
                    int idx = (int) ((alphaIndices >>> (i * 3)) & 0x7);
                    alphas[i] = alphaPalette[idx];
                }

                int c0 = buffer.getShort() & 0xFFFF;
                int c1 = buffer.getShort() & 0xFFFF;

                decodeRgb565(c0, colors, 0);
                decodeRgb565(c1, colors, 1);
                colors[2] = interpolateRgb(colors[0], colors[1], 2, 1, 3, 255);
                colors[3] = interpolateRgb(colors[0], colors[1], 1, 2, 3, 255);

                int bits = buffer.getInt();
                writeBlock(rgba, bx * 4, by * 4, width, height, colors, bits, alphas);
            }
        }
        return rgba;
    }

    private static void decodeRgb565(int val, int[] out, int index) {
        int r = (val >>> 11) & 0x1F;
        int g = (val >>> 5) & 0x3F;
        int b = val & 0x1F;

        int r8 = (r << 3) | (r >>> 2);
        int g8 = (g << 2) | (g >>> 4);
        int b8 = (b << 3) | (b >>> 2);

        out[index] = (r8 << 24) | (g8 << 16) | (b8 << 8) | 0xFF;
    }

    private static int interpolateRgb(int c0, int c1, int w0, int w1, int div, int alpha) {
        int r0 = (c0 >>> 24) & 0xFF;
        int g0 = (c0 >>> 16) & 0xFF;
        int b0 = (c0 >>> 8) & 0xFF;

        int r1 = (c1 >>> 24) & 0xFF;
        int g1 = (c1 >>> 16) & 0xFF;
        int b1 = (c1 >>> 8) & 0xFF;

        int r = (w0 * r0 + w1 * r1) / div;
        int g = (w0 * g0 + w1 * g1) / div;
        int b = (w0 * b0 + w1 * b1) / div;

        return (r << 24) | (g << 16) | (b << 8) | (alpha & 0xFF);
    }

    private static void writeBlock(
            byte[] dest,
            int startX, int startY,
            int width, int height,
            int[] colors,
            int bits,
            int[] explicitAlphas
    ) {
        for (int y = 0; y < 4; y++) {
            int py = startY + y;
            if (py >= height) break;

            for (int x = 0; x < 4; x++) {
                int px = startX + x;
                if (px >= width) break;

                int pixelIndex = y * 4 + x;
                int colorIndex = (bits >>> (pixelIndex * 2)) & 0x3;
                int color = colors[colorIndex];

                int a = (explicitAlphas != null) ? explicitAlphas[pixelIndex] : (color & 0xFF);
                int r = (color >>> 24) & 0xFF;
                int g = (color >>> 16) & 0xFF;
                int b = (color >>> 8) & 0xFF;

                int offset = (py * width + px) * 4;
                dest[offset] = (byte) r;
                dest[offset + 1] = (byte) g;
                dest[offset + 2] = (byte) b;
                dest[offset + 3] = (byte) a;
            }
        }
    }
}
