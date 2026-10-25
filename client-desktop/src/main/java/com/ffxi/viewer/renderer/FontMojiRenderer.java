package com.ffxi.viewer.renderer;

import com.ffxi.export.TextureDecoder;
import com.ffxi.io.ByteReader;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.TextureResource;
import com.ffxi.parser.DatParser;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact reproduction of FFXI / XIM authentic in-game font rendering.
 * Loads and decodes the authentic retail "font moji" (1024x2048) texture from ROM/0/1.DAT,
 * applying the exact proportional character widths, Shift-JIS / ASCII indexing,
 * and 2x color modulation from XIM (FontMojiHelper.kt and XimUiShader.kt).
 */
public final class FontMojiRenderer {

    public static final Color COLOR_WHITE = new Color(240, 245, 255);
    public static final Color COLOR_GOLD = new Color(255, 220, 100);
    public static final Color COLOR_INFO_BLUE = new Color(130, 195, 255);
    public static final Color COLOR_GREEN = new Color(140, 230, 140);
    public static final Color COLOR_RED = new Color(255, 120, 120);
    public static final Color COLOR_GREY = new Color(160, 170, 185);

    // Exact character width bounds (minX, maxX) from XIM FontMojiHelper.kt
    private static final int[][] CHAR_WIDTHS = new int[][]{
            {0, 4}, {0, 4}, {0, 4}, {0, 8}, {0, 7}, {0, 11}, {0, 11}, {0, 3}, {0, 7}, {0, 7},
            {0, 7}, {1, 7}, {0, 3}, {1, 6}, {1, 2}, {0, 7}, {0, 7}, {0, 7}, {0, 7}, {0, 7},
            {0, 7}, {0, 7}, {0, 7}, {0, 7}, {0, 7}, {0, 7}, {0, 4}, {0, 3}, {1, 7}, {1, 7},
            {1, 7}, {0, 7}, {0, 11}, {0, 9}, {0, 9}, {0, 8}, {1, 10}, {0, 8}, {0, 7}, {0, 9},
            {0, 9}, {0, 5}, {0, 8}, {0, 9}, {1, 8}, {0, 11}, {0, 8}, {0, 9}, {0, 8}, {0, 11},
            {0, 9}, {0, 8}, {0, 8}, {0, 8}, {0, 9}, {0, 12}, {0, 9}, {0, 8}, {0, 8}, {0, 7},
            {0, 7}, {0, 7}, {0, 7}, {0, 7}, {0, 5}, {0, 7}, {0, 7}, {0, 7}, {0, 7}, {0, 7},
            {0, 5}, {0, 7}, {0, 7}, {0, 3}, {0, 4}, {0, 7}, {0, 4}, {0, 10}, {0, 7}, {0, 8},
            {0, 7}, {0, 7}, {0, 5}, {0, 7}, {0, 5}, {0, 7}, {0, 7}, {0, 9}, {0, 7}, {0, 7},
            {0, 7}, {0, 5}, {1, 2}, {0, 7}, {0, 11}, {16, 0}, {16, 0}, {0, 4}, {0, 7}, {1, 4},
            {1, 4}, {6, 9}, {6, 8}, {6, 8}, {4, 12}, {6, 11}, {0, 7}, {1, 7}, {0, 7}, {0, 5},
            {0, 7}, {0, 7}, {0, 15}, {0, 15}, {4, 11}, {4, 13}, {4, 12}, {4, 15}, {4, 11}, {0, 15},
            {0, 12}, {0, 13}, {0, 15}, {1, 14}, {0, 15}, {6, 10}, {0, 15}, {0, 15}
    };

    private final BufferedImage mojiTexture;
    private final int texWidth;
    private final int texHeight;

    public FontMojiRenderer(BufferedImage mojiTexture) {
        this.mojiTexture = mojiTexture;
        this.texWidth = mojiTexture.getWidth();
        this.texHeight = mojiTexture.getHeight();
    }

    /**
     * Loads and initializes the authentic font renderer directly from ROM/0/1.DAT.
     */
    public static FontMojiRenderer load(Path ffxiRoot) throws IOException {
        Path datPath = ffxiRoot.resolve("ROM/0/1.DAT");
        byte[] data = Files.readAllBytes(datPath);
        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(data, "ROM/0/1.DAT"));
        List<TextureResource> list = new ArrayList<>();
        collectTextures(graph.rootDirectory(), list);

        TextureResource mojiRes = null;
        for (TextureResource tr : list) {
            if ("moji".equalsIgnoreCase(tr.sectionId().trim())) {
                mojiRes = tr;
                break;
            }
        }
        if (mojiRes == null) {
            throw new IllegalStateException("Section 'moji' (font moji) not found in ROM/0/1.DAT");
        }

        byte[] rgba = TextureDecoder.decode(data, mojiRes);
        if (rgba == null) {
            throw new IllegalStateException("Failed to decode 'moji' texture from ROM/0/1.DAT");
        }

        BufferedImage img = new BufferedImage(mojiRes.width(), mojiRes.height(), BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[mojiRes.width() * mojiRes.height()];
        for (int i = 0; i < px.length; i++) {
            int off = i * 4;
            int r = rgba[off] & 0xFF;
            int g = rgba[off + 1] & 0xFF;
            int b = rgba[off + 2] & 0xFF;
            int a = rgba[off + 3] & 0xFF;
            // Authentic 2.0x boost from XimUiShader (outColor = 2.0 * colorMask * ...)
            r = Math.min(255, r * 2);
            g = Math.min(255, g * 2);
            b = Math.min(255, b * 2);
            a = Math.min(255, a * 2);
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, mojiRes.width(), mojiRes.height(), px, 0, mojiRes.width());
        return new FontMojiRenderer(img);
    }

    public BufferedImage getTexture() {
        return mojiTexture;
    }

    /**
     * Maps character to glyph index in font moji grid (matches FontMojiHelper.mapShiftJisToIndex).
     */
    public static int mapCharToIndex(char c) {
        int upper = ((int) c >> 8) & 0xFF;
        int lower = ((int) c) & 0xFF;

        if (upper == 0) {
            return lower - 0x20;
        } else if (upper == 0x81) {
            if (lower <= 0x7E) return 0x060 + (lower - 0x40 - 0x00);
            if (lower <= 0xAC) return 0x060 + (lower - 0x40 - 0x01);
            if (lower <= 0xBF) return 0x060 + (lower - 0x40 - 0x0C);
            if (lower <= 0xCE) return 0x060 + (lower - 0x40 - 0x14);
            if (lower <= 0xE8) return 0x060 + (lower - 0x40 - 0x1F);
            if (lower <= 0xF7) return 0x060 + (lower - 0x40 - 0x26);
            if (lower <= 0xFC) return 0x060 + (lower - 0x40 - 0x2A);
            return '?' - 0x20;
        } else if (upper == 0x82) {
            if (lower <= 0x58) return 0x0F3 + (lower - 0x40 - 0x0F);
            if (lower <= 0x79) return 0x0F3 + (lower - 0x40 - 0x16);
            if (lower <= 0x9A) return 0x0F3 + (lower - 0x40 - 0x1D);
            if (lower <= 0xF1) return 0x0F3 + (lower - 0x40 - 0x21);
            return '?' - 0x20;
        } else if (upper == 0x83) {
            if (lower <= 0x7E) return 0x184 + (lower - 0x40 - 0x00);
            if (lower <= 0x96) return 0x184 + (lower - 0x40 - 0x01);
            if (lower <= 0xB6) return 0x184 + (lower - 0x40 - 0x09);
            if (lower <= 0xD6) return 0x184 + (lower - 0x40 - 0x11);
            return '?' - 0x20;
        }
        return '?' - 0x20;
    }

    /**
     * Calculates width of a rendered text line in pixels.
     */
    public int measureString(String text, int scale) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (char ch : text.toCharArray()) {
            if (ch == '\n') break;
            int index = mapCharToIndex(ch);
            if (index < 0) continue;
            int minX = 0, maxX = 15;
            if (index < CHAR_WIDTHS.length) {
                minX = CHAR_WIDTHS[index][0];
                maxX = CHAR_WIDTHS[index][1];
            }
            int gw = (maxX - minX) + 1;
            width += (gw + 1) * scale;
        }
        return width;
    }

    /**
     * Renders authentic FFXI text string directly onto the destination image.
     */
    public void drawString(BufferedImage dest, String text, int startX, int startY, Color color, int scale) {
        if (text == null || text.isEmpty() || dest == null) return;

        int curX = startX;
        int curY = startY;

        float cr = color.getRed() / 255.0f;
        float cg = color.getGreen() / 255.0f;
        float cb = color.getBlue() / 255.0f;
        float ca = color.getAlpha() / 255.0f;

        for (char ch : text.toCharArray()) {
            if (ch == '\n') {
                curX = startX;
                curY += 16 * scale; // Exact XIM line height: currentPosition.y += 16f
                continue;
            }

            int index = mapCharToIndex(ch);
            if (index < 0) continue;

            int minX = 0, maxX = 15;
            if (index < CHAR_WIDTHS.length) {
                minX = CHAR_WIDTHS[index][0];
                maxX = CHAR_WIDTHS[index][1];
            }
            int gw = (maxX - minX) + 1;
            int gh = 16;

            int gx = (index % 64) * 16;
            int gy = (index / 64) * 16;

            if (ch != ' ') {
                for (int y = 0; y < gh; y++) {
                    for (int x = 0; x < gw; x++) {
                        int srcX = gx + minX + x;
                        int srcY = gy + y;
                        if (srcX >= texWidth || srcY >= texHeight) continue;

                        int srcColor = mojiTexture.getRGB(srcX, srcY);
                        int sa = (srcColor >>> 24) & 0xFF;
                        if (sa < 8) continue; // transparent fringe discard

                        int sr = (srcColor >>> 16) & 0xFF;
                        int sg = (srcColor >>> 8) & 0xFF;
                        int sb = srcColor & 0xFF;

                        // Modulate with target color
                        float alphaNorm = (sa / 255.0f) * ca;
                        int modR = Math.min(255, (int) (sr * cr));
                        int modG = Math.min(255, (int) (sg * cg));
                        int modB = Math.min(255, (int) (sb * cb));

                        for (int dy = 0; dy < scale; dy++) {
                            for (int dx = 0; dx < scale; dx++) {
                                int px = curX + x * scale + dx;
                                int py = curY + y * scale + dy;
                                if (px >= 0 && px < dest.getWidth() && py >= 0 && py < dest.getHeight()) {
                                    int dstColor = dest.getRGB(px, py);
                                    int dr = (dstColor >>> 16) & 0xFF;
                                    int dg = (dstColor >>> 8) & 0xFF;
                                    int db = dstColor & 0xFF;

                                    int outR = Math.min(255, (int) (modR * alphaNorm + dr * (1.0f - alphaNorm)));
                                    int outG = Math.min(255, (int) (modG * alphaNorm + dg * (1.0f - alphaNorm)));
                                    int outB = Math.min(255, (int) (modB * alphaNorm + db * (1.0f - alphaNorm)));

                                    dest.setRGB(px, py, (0xFF << 24) | (outR << 16) | (outG << 8) | outB);
                                }
                            }
                        }
                    }
                }
            }
            curX += (gw + 1) * scale;
        }
    }

    private static void collectTextures(DirectoryResource dir, List<TextureResource> out) {
        if (dir == null) return;
        for (ResourceNode c : dir.children()) {
            if (c instanceof TextureResource tr) out.add(tr);
            else if (c instanceof DirectoryResource dr) collectTextures(dr, out);
        }
    }
}
