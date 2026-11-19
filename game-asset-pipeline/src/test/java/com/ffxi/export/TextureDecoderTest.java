package com.ffxi.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffxi.model.TextureResource;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TextureDecoderTest {
    private static final int SECTION_START = 0x20;
    /** Bytes consumed by the common texture header after the 0x10 section header. */
    private static final int TEXTURE_HEADER = 57;
    private static final int DXT_HEADER = 12;

    // ---------------------------------------------------------------- helpers

    private static byte[] dat(int type, int extraHeader, byte[] payload) {
        byte[] data = new byte[SECTION_START + 0x10 + TEXTURE_HEADER + extraHeader + payload.length];
        System.arraycopy(payload, 0, data, SECTION_START + 0x10 + TEXTURE_HEADER + extraHeader, payload.length);
        return data;
    }

    private static TextureResource resource(int type, int w, int h, int bitCount, String dxt, int total) {
        return new TextureResource("TEX0", "S20_Texture", SECTION_START, total - SECTION_START, total - SECTION_START - 0x10,
                type, "tex", -1, w, h, bitCount, dxt);
    }

    private static int[] px(byte[] rgba, int w, int x, int y) {
        int o = (y * w + x) * 4;
        return new int[] {rgba[o] & 0xFF, rgba[o + 1] & 0xFF, rgba[o + 2] & 0xFF, rgba[o + 3] & 0xFF};
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xFFFF);
        le16(o, v >>> 16);
    }

    private static byte[] colorBlock(int c0, int c1, int... rowBytes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le16(o, c0);
        le16(o, c1);
        for (int b : rowBytes) {
            o.write(b);
        }
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static byte[] dxtDat(String dxt, int w, int h, byte[] payload) {
        byte[] data = dat(0xA1, DXT_HEADER, payload);
        return data;
    }

    // ------------------------------------------------------------------- DXT1

    @Test
    void dxt1FourColorBlock() {
        // c0 = pure red, c1 = pure blue, c0 > c1 => 4-colour mode
        byte[] payload = colorBlock(0xF800, 0x001F, 0xE4, 0xFF, 0x00, 0x55);
        byte[] data = dxtDat("1TXD", 4, 4, payload);
        byte[] rgba = TextureDecoder.decode(data, resource(0xA1, 4, 4, 0, "1TXD", data.length));

        assertThat(rgba).hasSize(4 * 4 * 4);
        // row 0: idx 0,1,2,3 (cols 0..3)
        assertThat(px(rgba, 4, 0, 0)).containsExactly(255, 0, 0, 255);
        assertThat(px(rgba, 4, 1, 0)).containsExactly(0, 0, 255, 255);
        assertThat(px(rgba, 4, 2, 0)).containsExactly(170, 0, 85, 255);
        assertThat(px(rgba, 4, 3, 0)).containsExactly(85, 0, 170, 255);
        // row 1: all idx 3, row 2: all idx 0, row 3: all idx 1
        for (int x = 0; x < 4; x++) {
            assertThat(px(rgba, 4, x, 1)).containsExactly(85, 0, 170, 255);
            assertThat(px(rgba, 4, x, 2)).containsExactly(255, 0, 0, 255);
            assertThat(px(rgba, 4, x, 3)).containsExactly(0, 0, 255, 255);
        }
    }

    @Test
    void dxt1ThreeColorModeHasTransparentIndex3() {
        // c0 <= c1 => 3-colour + transparent black
        byte[] payload = colorBlock(0x001F, 0xF800, 0xE4, 0, 0, 0);
        byte[] data = dxtDat("1TXD", 4, 4, payload);
        byte[] rgba = TextureDecoder.decode(data, resource(0xA1, 4, 4, 0, "1TXD", data.length));

        assertThat(px(rgba, 4, 2, 0)).containsExactly(127, 0, 127, 255); // midpoint
        assertThat(px(rgba, 4, 3, 0)).containsExactly(0, 0, 0, 0);
    }

    @Test
    void dxt1MatchesLegacyBigEndianImplementation() {
        Random rnd = new Random(42);
        int w = 16;
        int h = 8;
        byte[] payload = new byte[(w / 4) * (h / 4) * 8];
        rnd.nextBytes(payload);
        byte[] actual = TextureDecoder.decodeDxt1(payload, 0, w, h);
        assertThat(actual).isEqualTo(legacyDxt1(payload, w, h));
    }

    @Test
    void dxt1NonMultipleOfFourDimensionsAreCropped() {
        byte[] payload = colorBlock(0xF800, 0x001F, 0, 0, 0, 0);
        byte[] out = TextureDecoder.decodeDxt1(payload, 0, 3, 2);
        assertThat(out).hasSize(3 * 2 * 4);
        assertThat(px(out, 3, 2, 1)).containsExactly(255, 0, 0, 255);
    }

    /** Verbatim port of the previous (convoluted, big-endian index) DXT1 decoder, kept as a reference. */
    private static byte[] legacyDxt1(byte[] src, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        int pos = 0;
        for (int blockY = 0; blockY < height; blockY += 4) {
            for (int blockX = 0; blockX < width; blockX += 4) {
                int color0 = (src[pos] & 0xFF) | ((src[pos + 1] & 0xFF) << 8);
                int color1 = (src[pos + 2] & 0xFF) | ((src[pos + 3] & 0xFF) << 8);
                pos += 4;
                int[] r = new int[4];
                int[] g = new int[4];
                int[] b = new int[4];
                int[] a = {255, 255, 255, 255};
                r[0] = ((color0 >>> 11) & 0x1F) * 255 / 31;
                g[0] = ((color0 >>> 5) & 0x3F) * 255 / 63;
                b[0] = (color0 & 0x1F) * 255 / 31;
                r[1] = ((color1 >>> 11) & 0x1F) * 255 / 31;
                g[1] = ((color1 >>> 5) & 0x3F) * 255 / 63;
                b[1] = (color1 & 0x1F) * 255 / 31;
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
                    a[3] = 0;
                }
                int indices = ((src[pos] & 0xFF) << 24) | ((src[pos + 1] & 0xFF) << 16)
                        | ((src[pos + 2] & 0xFF) << 8) | (src[pos + 3] & 0xFF);
                pos += 4;
                for (int y = blockY, count = 15; y < blockY + 4; y++) {
                    for (int x = blockX + 3; x >= blockX; x--, count--) {
                        int pi = (indices >>> (count * 2)) & 3;
                        if (x < width && y < height) {
                            int o = (y * width + x) * 4;
                            rgba[o] = (byte) r[pi];
                            rgba[o + 1] = (byte) g[pi];
                            rgba[o + 2] = (byte) b[pi];
                            rgba[o + 3] = (byte) a[pi];
                        }
                    }
                }
            }
        }
        return rgba;
    }

    // ------------------------------------------------------------------- DXT3

    @Test
    void dxt3AlphaRowOrderAndMaxAlpha() {
        // alpha rows (little-endian: row 0 is the first two bytes, nibble 0 = lowest nibble = column 0)
        // row0: F F F F -> 0xFFFF ; row1: 0 0 0 0 ; row2: 8 8 8 8 ; row3: 0 1 2 3 -> 0x3210
        byte[] alpha = {(byte) 0xFF, (byte) 0xFF, 0x00, 0x00, (byte) 0x88, (byte) 0x88, 0x10, 0x32};
        byte[] payload = concat(alpha, colorBlock(0xFFFF, 0xFFFF, 0, 0, 0, 0));
        byte[] data = dxtDat("3TXD", 4, 4, payload);
        byte[] rgba = TextureDecoder.decode(data, resource(0xA1, 4, 4, 0, "3TXD", data.length));

        assertThat(rgba).hasSize(64);
        for (int x = 0; x < 4; x++) {
            assertThat(px(rgba, 4, x, 0)[3]).as("row0 alpha 0xF -> 255").isEqualTo(255);
            assertThat(px(rgba, 4, x, 1)[3]).as("row1 alpha 0").isEqualTo(0);
            assertThat(px(rgba, 4, x, 2)[3]).as("row2 alpha 8").isEqualTo(8 * 255 / 15);
            assertThat(px(rgba, 4, x, 3)[3]).as("row3 col " + x).isEqualTo(x * 255 / 15);
        }
        assertThat(px(rgba, 4, 0, 0)).startsWith(255, 255, 255);
    }

    @Test
    void dxt3EveryNibbleMapsToItsOwnPixel() {
        long bits = 0;
        for (int i = 0; i < 16; i++) {
            bits |= ((long) i) << (4 * i);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le32(o, (int) bits);
        le32(o, (int) (bits >>> 32));
        byte[] payload = concat(o.toByteArray(), colorBlock(0, 0, 0, 0, 0, 0));
        byte[] rgba = TextureDecoder.decodeDxt3(payload, 0, 4, 4);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int nibble = r * 4 + c;
                assertThat(px(rgba, 4, c, r)[3]).isEqualTo(nibble * 255 / 15);
            }
        }
    }

    // ------------------------------------------------------------------- DXT5

    @Test
    void dxt5EightInterpolatedAlphas() {
        // a0=255 > a1=0 => 8 alpha mode; pixel i uses index i % 8
        byte[] alpha = alphaBlock(255, 0, idx -> idx % 8);
        byte[] payload = concat(alpha, colorBlock(0xFFFF, 0xFFFF, 0, 0, 0, 0));
        byte[] data = dxtDat("5TXD", 4, 4, payload);
        byte[] rgba = TextureDecoder.decode(data, resource(0xA1, 4, 4, 0, "5TXD", data.length));

        int[] expected = {255, 0, 218, 182, 145, 109, 72, 36};
        for (int i = 0; i < 16; i++) {
            assertThat(px(rgba, 4, i % 4, i / 4)[3]).as("pixel " + i).isEqualTo(expected[i % 8]);
        }
        assertThat(px(rgba, 4, 0, 0)).startsWith(255, 255, 255);
    }

    @Test
    void dxt5SixInterpolatedAlphasWithZeroAndOpaqueEndpoints() {
        // a0=100 <= a1=200 => 6 alpha mode with explicit 0 and 255
        byte[] alpha = alphaBlock(100, 200, idx -> idx % 8);
        byte[] payload = concat(alpha, colorBlock(0xFFFF, 0xFFFF, 0, 0, 0, 0));
        byte[] rgba = TextureDecoder.decodeDxt5(payload, 0, 4, 4);

        int[] expected = {100, 200, 120, 140, 160, 180, 0, 255};
        for (int i = 0; i < 16; i++) {
            assertThat(px(rgba, 4, i % 4, i / 4)[3]).as("pixel " + i).isEqualTo(expected[i % 8]);
        }
    }

    @Test
    void dxt5ColourBlockAlwaysUsesFourColourMode() {
        // c0 < c1 would be 3-colour in DXT1, but BC3 always interpolates 4 colours
        byte[] payload = concat(alphaBlock(255, 255, idx -> 0), colorBlock(0x001F, 0xF800, 0xE4, 0, 0, 0));
        byte[] rgba = TextureDecoder.decodeDxt5(payload, 0, 4, 4);
        assertThat(px(rgba, 4, 2, 0)).containsExactly(85, 0, 170, 255);
        assertThat(px(rgba, 4, 3, 0)).containsExactly(170, 0, 85, 255);
    }

    private static byte[] alphaBlock(int a0, int a1, java.util.function.IntUnaryOperator indexOf) {
        long bits = 0;
        for (int i = 0; i < 16; i++) {
            bits |= ((long) (indexOf.applyAsInt(i) & 7)) << (3 * i);
        }
        byte[] b = new byte[8];
        b[0] = (byte) a0;
        b[1] = (byte) a1;
        for (int i = 0; i < 6; i++) {
            b[2 + i] = (byte) (bits >>> (8 * i));
        }
        return b;
    }

    // ------------------------------------------------------- paletted / direct

    private static byte[] palettePayload(int prefix, int[] colorsBgra, byte[] indices) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (prefix > 0) {
            le32(o, 0x01);
        }
        for (int i = 0; i < 256; i++) {
            le32(o, i < colorsBgra.length ? colorsBgra[i] : 0);
        }
        o.writeBytes(indices);
        return o.toByteArray();
    }

    @Test
    void palette0x91DecodesAndFlipsVertically() {
        int[] palette = {0xFF112233, 0x80445566, 0x00778899};
        // 2x2, indices top row: 0,1 ; bottom row: 2,0
        byte[] data = dat(0x91, 0, palettePayload(0, palette, new byte[] {0, 1, 2, 0}));
        byte[] rgba = TextureDecoder.decode(data, resource(0x91, 2, 2, 8, "", data.length));

        assertThat(rgba).hasSize(16);
        // output row 0 is the LAST source row
        assertThat(px(rgba, 2, 0, 0)).containsExactly(0x77, 0x88, 0x99, 0x00);
        assertThat(px(rgba, 2, 1, 0)).containsExactly(0x11, 0x22, 0x33, 0xFF);
        assertThat(px(rgba, 2, 0, 1)).containsExactly(0x11, 0x22, 0x33, 0xFF);
        assertThat(px(rgba, 2, 1, 1)).containsExactly(0x44, 0x55, 0x66, 0x80);
    }

    @Test
    void paletteAlt0xB1With8BitSkipsPrefixThenReadsPalette() {
        int[] palette = {0xFFAABBCC, 0xFF010203};
        byte[] data = dat(0xB1, 0, palettePayload(4, palette, new byte[] {1, 0}));
        byte[] rgba = TextureDecoder.decode(data, resource(0xB1, 2, 1, 8, "", data.length));

        assertThat(px(rgba, 2, 0, 0)).containsExactly(0x01, 0x02, 0x03, 0xFF);
        assertThat(px(rgba, 2, 1, 0)).containsExactly(0xAA, 0xBB, 0xCC, 0xFF);
    }

    @Test
    void paletteAlt0xB1With32BitSkipsFourBytePrefixBeforeDirectBgra() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le32(o, 0xDEADBEEF); // 4-byte prefix that must be skipped
        le32(o, 0x80102030); // pixel 0: A=0x80 R=0x10 G=0x20 B=0x30
        le32(o, 0xFF405060); // pixel 1
        byte[] data = dat(0xB1, 0, o.toByteArray());
        byte[] rgba = TextureDecoder.decode(data, resource(0xB1, 2, 1, 32, "", data.length));

        assertThat(rgba).isNotNull();
        assertThat(px(rgba, 2, 0, 0)).containsExactly(0x10, 0x20, 0x30, 0x80);
        assertThat(px(rgba, 2, 1, 0)).containsExactly(0x40, 0x50, 0x60, 0xFF);
    }

    @Test
    void palette0x91With32BitHasNoPrefix() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le32(o, 0x80102030);
        byte[] data = dat(0x91, 0, o.toByteArray());
        byte[] rgba = TextureDecoder.decode(data, resource(0x91, 1, 1, 32, "", data.length));
        assertThat(px(rgba, 1, 0, 0)).containsExactly(0x10, 0x20, 0x30, 0x80);
    }

    // -------------------------------------------------------------- robustness

    @Test
    void truncatedDataReturnsNullInsteadOfThrowing() {
        byte[] full = dxtDat("1TXD", 8, 8, new byte[4 * 8]);
        byte[] truncated = java.util.Arrays.copyOf(full, full.length - 1);
        assertThat(TextureDecoder.decode(truncated, resource(0xA1, 8, 8, 0, "1TXD", truncated.length))).isNull();

        byte[] pal = dat(0x91, 0, new byte[100]);
        assertThat(TextureDecoder.decode(pal, resource(0x91, 4, 4, 8, "", pal.length))).isNull();

        byte[] direct = dat(0xB1, 0, new byte[7]);
        assertThat(TextureDecoder.decode(direct, resource(0xB1, 2, 1, 32, "", direct.length))).isNull();

        // header itself truncated
        byte[] tiny = new byte[SECTION_START + 0x10 + 5];
        assertThat(TextureDecoder.decode(tiny, resource(0x91, 2, 2, 8, "", tiny.length))).isNull();
    }

    @Test
    void absurdDimensionsOnSmallDataReturnNullWithoutAllocating() {
        byte[] data = dxtDat("1TXD", 0, 0, new byte[8]);
        assertThat(TextureDecoder.decode(data, resource(0xA1, 30000, 30000, 0, "1TXD", data.length))).isNull();
        assertThat(TextureDecoder.decode(data, resource(0xA1, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, "1TXD", data.length)))
                .isNull();
    }

    @Test
    void unsupportedTypesAndArgumentsReturnNull() {
        byte[] data = dxtDat("9TXD", 4, 4, new byte[16]);
        assertThat(TextureDecoder.decode(data, resource(0xA1, 4, 4, 0, "9TXD", data.length))).isNull();
        assertThat(TextureDecoder.decode(data, resource(0x42, 4, 4, 0, "", data.length))).isNull();
        assertThat(TextureDecoder.decode(null, resource(0xA1, 4, 4, 0, "1TXD", 10))).isNull();
        assertThat(TextureDecoder.decode(data, null)).isNull();
        assertThat(TextureDecoder.decode(data, resource(0xA1, 0, 4, 0, "1TXD", data.length))).isNull();
    }
}
