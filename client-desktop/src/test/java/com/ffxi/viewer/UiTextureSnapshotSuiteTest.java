package com.ffxi.viewer;

import com.ffxi.export.TextureDecoder;
import com.ffxi.io.ByteReader;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.TextureResource;
import com.ffxi.parser.DatParser;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suite that extracts, decodes, and renders authentic retail FFXI user interface (UI)
 * textures, HUD elements, menu skins, radar/compass, status icons, and dialog frames,
 * rendering all typography strictly using the authentic retail "font moji" game asset.
 */
public class UiTextureSnapshotSuiteTest {

    private static final Path FFXI_ROOT = Path.of("/home/samuel_l_hyman/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI");
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    private FontMojiRenderer font;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        this.font = FontMojiRenderer.load(FFXI_ROOT);
        assertNotNull(this.font, "Authentic FontMojiRenderer must load from ROM/0/1.DAT");
    }

    @Test
    @DisplayName("Extract and render authentic retail FFXI UI textures and HUD elements")
    void testExtractAndRenderAllUiTextures() throws Exception {
        // 1. Render Core HUD & Menu System Texture Contact Sheet
        renderMenuSystemSheet();

        // 2. Render Status & Action Icons Sheet (Buffs, Debuffs, Job Abilities, Spells)
        renderStatusAndActionIconsSheet();

        // 3. Render 8 Elemental Icons (Enlarged Pixel Art)
        renderElementalIcons();

        // 4. Render Radar Compass & Navigation HUD
        renderRadarCompass();

        // 5. Render Menu Frame Border Styles (ROM/0/14 through ROM/0/21)
        renderMenuFrameStyles();

        // 6. Render Assembled Dialog Box Window Mockup using authentic font & frames
        renderDialogWindowMockup();
    }

    private void renderMenuSystemSheet() throws Exception {
        Path datPath = FFXI_ROOT.resolve("ROM/0/1.DAT");
        byte[] data = Files.readAllBytes(datPath);
        Map<String, BufferedImage> textures = loadDecodedTextures(data, "ROM/0/1.DAT");

        int width = 1600;
        int height = 1200;
        BufferedImage sheet = createSlateCanvas(width, height);
        Graphics2D g = sheet.createGraphics();

        drawHeader(sheet, "RETAIL FFXI UI SYSTEM TEXTURES (ROM/0/1.DAT)");

        // Large Menu Textures (256x256)
        int curX = 40;
        int curY = 80;
        String[] menuKeys = new String[]{"menu", "men1", "men2", "mn6f", "keyt"};
        for (String key : menuKeys) {
            BufferedImage img = textures.get(key);
            if (img != null) {
                drawTitledBox(sheet, curX, curY, img.getWidth(), img.getHeight(), key.toUpperCase() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
                g.drawImage(img, curX, curY + 24, null);
                curX += img.getWidth() + 30;
                if (curX + 280 > width) {
                    curX = 40;
                    curY += 310;
                }
            }
        }

        // Gauges, Buttons, Dialog Frame
        curX = 40;
        curY = 420;
        String[] midKeys = new String[]{"gaug", "butt", "dg_f", "msgi", "colo", "cico", "fepp", "mark", "lrbu", "scan"};
        for (String key : midKeys) {
            BufferedImage img = textures.get(key);
            if (img != null) {
                int scale = (img.getWidth() <= 64) ? 2 : 1;
                int dw = img.getWidth() * scale;
                int dh = img.getHeight() * scale;
                drawTitledBox(sheet, curX, curY, dw, dh, key.toUpperCase() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
                g.drawImage(img, curX, curY + 24, dw, dh, null);
                curX += dw + 25;
                if (curX + 160 > width) {
                    curX = 40;
                    curY += 180;
                }
            }
        }

        // Small icons row (scaled 3x)
        curX = 40;
        curY = 800;
        String[] smallKeys = new String[]{"comp", "yubi", "maru", "ite1", "warn", "netm", "phot", "item", "maph", "maps", "mapa"};
        for (String key : smallKeys) {
            BufferedImage img = textures.get(key);
            if (img != null) {
                int scale = 3;
                int dw = img.getWidth() * scale;
                int dh = img.getHeight() * scale;
                drawTitledBox(sheet, curX, curY, dw, dh, key.toUpperCase());
                g.drawImage(img, curX, curY + 24, dw, dh, null);
                curX += dw + 20;
                if (curX + 120 > width) {
                    curX = 40;
                    curY += 150;
                }
            }
        }

        g.dispose();
        saveSnapshot(sheet, "ui_menu_system_elements.png");
    }

    private void renderStatusAndActionIconsSheet() throws Exception {
        Path dat13 = FFXI_ROOT.resolve("ROM/0/13.DAT");
        byte[] data13 = Files.readAllBytes(dat13);
        Map<String, BufferedImage> textures13 = loadDecodedTextures(data13, "ROM/0/13.DAT");

        Path dat1 = FFXI_ROOT.resolve("ROM/0/1.DAT");
        byte[] data1 = Files.readAllBytes(dat1);
        Map<String, BufferedImage> textures1 = loadDecodedTextures(data1, "ROM/0/1.DAT");

        int width = 1440;
        int height = 1080;
        BufferedImage sheet = createSlateCanvas(width, height);
        Graphics2D g = sheet.createGraphics();

        drawHeader(sheet, "STATUS EFFECTS, MAGIC, AND JOB ABILITIES (ROM/0/13.DAT)");

        // 1. Unit Status Icons (usta)
        BufferedImage usta = textures13.get("usta");
        if (usta == null) usta = textures1.get("usta");
        if (usta != null) {
            int scale = 4;
            int dw = usta.getWidth() * scale;
            int dh = usta.getHeight() * scale;
            drawTitledBox(sheet, 40, 80, dw, dh, "UNIT STATUS / BUFF & DEBUFF ICONS [USTA] (" + usta.getWidth() + "x" + usta.getHeight() + " -> " + scale + "x)");
            g.drawImage(usta, 40, 104, dw, dh, null);
        }

        // 2. Action Icons (Magic, Blue Magic, Ninja, Dice, Cards, Emotes, Mounts)
        int curX = 40;
        int curY = 400;
        String[] actionKeys = new String[]{"magi", "blu ", "ninj", "dice", "card", "emot", "mnt "};
        for (String key : actionKeys) {
            BufferedImage img = textures13.get(key);
            if (img != null) {
                int scale = 4;
                int dw = img.getWidth() * scale;
                int dh = img.getHeight() * scale;
                drawTitledBox(sheet, curX, curY, dw, dh, key.trim().toUpperCase() + " (" + img.getWidth() + "x" + img.getHeight() + " -> " + scale + "x)");
                g.drawImage(img, curX, curY + 24, dw, dh, null);
                curX += dw + 25;
                if (curX + 200 > width) {
                    curX = 40;
                    curY += dh + 50;
                }
            }
        }

        g.dispose();
        saveSnapshot(sheet, "ui_status_and_action_icons.png");
    }

    private void renderElementalIcons() throws Exception {
        Path dat1 = FFXI_ROOT.resolve("ROM/0/1.DAT");
        byte[] data1 = Files.readAllBytes(dat1);
        Map<String, BufferedImage> textures = loadDecodedTextures(data1, "ROM/0/1.DAT");

        record Elem(String key, String name) {}
        Elem[] elems = new Elem[]{
                new Elem("elfi", "FIRE"),
                new Elem("elic", "ICE"),
                new Elem("elwi", "WIND"),
                new Elem("elea", "EARTH"),
                new Elem("elth", "LIGHTNING"),
                new Elem("elwa", "WATER"),
                new Elem("elho", "LIGHT"),
                new Elem("elda", "DARK")
        };

        int width = 1200;
        int height = 360;
        BufferedImage sheet = createSlateCanvas(width, height);
        Graphics2D g = sheet.createGraphics();

        drawHeader(sheet, "RETAIL ELEMENTAL AFFINITY SPHERES (16x16 -> 6x ZOOM)");

        int curX = 50;
        int curY = 80;
        int scale = 6;

        for (Elem e : elems) {
            BufferedImage img = textures.get(e.key);
            if (img != null) {
                int dw = img.getWidth() * scale;
                int dh = img.getHeight() * scale;
                drawTitledBox(sheet, curX, curY, dw, dh, e.name);
                g.drawImage(img, curX, curY + 24, dw, dh, null);
                curX += dw + 45;
            }
        }

        g.dispose();
        saveSnapshot(sheet, "ui_elemental_icons.png");
    }

    private void renderRadarCompass() throws Exception {
        Path dat1 = FFXI_ROOT.resolve("ROM/0/1.DAT");
        byte[] data1 = Files.readAllBytes(dat1);
        Map<String, BufferedImage> textures = loadDecodedTextures(data1, "ROM/0/1.DAT");

        BufferedImage comp = textures.get("comp");
        BufferedImage yubi = textures.get("yubi");
        BufferedImage mark = textures.get("mark");

        int width = 800;
        int height = 500;
        BufferedImage sheet = createSlateCanvas(width, height);
        Graphics2D g = sheet.createGraphics();

        drawHeader(sheet, "RETAIL RADAR COMPASS & TARGET MARKERS");

        if (comp != null) {
            int scale = 8;
            int dw = comp.getWidth() * scale;
            int dh = comp.getHeight() * scale;
            drawTitledBox(sheet, 50, 80, dw, dh, "RADAR COMPASS (32x32 -> 8x ZOOM)");
            g.drawImage(comp, 50, 104, dw, dh, null);
        }

        if (yubi != null) {
            int scale = 6;
            int dw = yubi.getWidth() * scale;
            int dh = yubi.getHeight() * scale;
            drawTitledBox(sheet, 350, 80, dw, dh, "SELECTION FINGER CURSOR (6x)");
            g.drawImage(yubi, 350, 104, dw, dh, null);
        }

        if (mark != null) {
            int scale = 10;
            int dw = mark.getWidth() * scale;
            int dh = mark.getHeight() * scale;
            drawTitledBox(sheet, 350, 260, dw, dh, "TARGET RETICLE (10x)");
            g.drawImage(mark, 350, 284, dw, dh, null);
        }

        g.dispose();
        saveSnapshot(sheet, "ui_radar_compass.png");
    }

    private void renderMenuFrameStyles() throws Exception {
        int width = 1600;
        int height = 900;
        BufferedImage sheet = createSlateCanvas(width, height);
        Graphics2D g = sheet.createGraphics();

        drawHeader(sheet, "RETAIL MENU WINDOW FRAME THEMES (ROM/0/14.DAT THROUGH ROM/0/21.DAT)");

        String[] themeNames = new String[]{
                "Pattern 1 (Classic Blue)",
                "Pattern 2 (Gold Gilt)",
                "Pattern 3 (Silver Steel)",
                "Pattern 4 (Royal Red)",
                "Pattern 5 (Emerald Green)",
                "Pattern 6 (Shadow Dark)",
                "Pattern 7 (Wood Grain)",
                "Pattern 8 (Ornate Amethyst)"
        };

        int curX = 40;
        int curY = 80;

        for (int i = 0; i < 8; i++) {
            int romNum = 14 + i;
            Path p = FFXI_ROOT.resolve(String.format("ROM/0/%d.DAT", romNum));
            if (!Files.exists(p)) continue;

            byte[] data = Files.readAllBytes(p);
            Map<String, BufferedImage> textures = loadDecodedTextures(data, p.getFileName().toString());

            BufferedImage corn = textures.get("corn");
            BufferedImage hfr1 = textures.get("hfr1");
            BufferedImage vfr1 = textures.get("vfr1");
            BufferedImage newt = textures.get("newt");

            int boxW = 350;
            int boxH = 160;
            drawTitledBox(sheet, curX, curY, boxW, boxH, themeNames[i] + " [ROM/0/" + romNum + ".DAT]");

            // Draw assembled mini-window
            int winX = curX + 20;
            int winY = curY + 35;
            int winW = 310;
            int winH = 130;

            // Fill window background with newt pattern if available
            if (newt != null) {
                for (int bx = winX; bx < winX + winW; bx += newt.getWidth()) {
                    for (int by = winY; by < winY + winH; by += newt.getHeight()) {
                        g.drawImage(newt, bx, by, null);
                    }
                }
            } else {
                g.setColor(new Color(20, 28, 44, 220));
                g.fillRect(winX, winY, winW, winH);
            }

            // Top/bottom frame strips
            if (hfr1 != null) {
                int fw = hfr1.getWidth();
                int fh = hfr1.getHeight();
                for (int fx = winX + 16; fx < winX + winW - 16; fx += fw) {
                    g.drawImage(hfr1, fx, winY, null);
                    g.drawImage(hfr1, fx, winY + winH - fh, null);
                }
            }

            // Left/right frame strips
            if (vfr1 != null) {
                int fw = vfr1.getWidth();
                int fh = vfr1.getHeight();
                for (int fy = winY + 16; fy < winY + winH - 16; fy += fh) {
                    g.drawImage(vfr1, winX, fy, null);
                    g.drawImage(vfr1, winX + winW - fw, fy, null);
                }
            }

            // 4 corners
            if (corn != null) {
                int cw = corn.getWidth();
                int ch = corn.getHeight();
                g.drawImage(corn, winX, winY, null);
                g.drawImage(corn, winX + winW - cw, winY, null);
                g.drawImage(corn, winX, winY + winH - ch, null);
                g.drawImage(corn, winX + winW - cw, winY + winH - ch, null);
            }

            curX += boxW + 40;
            if (curX + boxW > width) {
                curX = 40;
                curY += boxH + 45;
            }
        }

        g.dispose();
        saveSnapshot(sheet, "ui_menu_frame_styles.png");
    }

    private void renderDialogWindowMockup() throws Exception {
        Path dat14 = FFXI_ROOT.resolve("ROM/0/14.DAT");
        byte[] data14 = Files.readAllBytes(dat14);
        Map<String, BufferedImage> textures = loadDecodedTextures(data14, "ROM/0/14.DAT");

        Path dat1 = FFXI_ROOT.resolve("ROM/0/1.DAT");
        byte[] data1 = Files.readAllBytes(dat1);
        Map<String, BufferedImage> hud = loadDecodedTextures(data1, "ROM/0/1.DAT");

        int width = 1280;
        int height = 720;
        BufferedImage sheet = createSlateCanvas(width, height);
        Graphics2D g = sheet.createGraphics();

        drawHeader(sheet, "AUTHENTIC RETAIL DIALOG WINDOW COMPOSITION");

        BufferedImage corn = textures.get("corn");
        BufferedImage hfr1 = textures.get("hfr1");
        BufferedImage vfr1 = textures.get("vfr1");
        BufferedImage newt = textures.get("newt");
        BufferedImage yubi = hud.get("yubi");

        // Main Dialog Window
        int winX = 140;
        int winY = 160;
        int winW = 1000;
        int winH = 400;

        // Background tiling
        if (newt != null) {
            for (int x = winX; x < winX + winW; x += newt.getWidth()) {
                for (int y = winY; y < winY + winH; y += newt.getHeight()) {
                    g.drawImage(newt, x, y, null);
                }
            }
        } else {
            g.setColor(new Color(14, 20, 32, 230));
            g.fillRect(winX, winY, winW, winH);
        }

        // Horizontal borders
        if (hfr1 != null) {
            int fw = hfr1.getWidth();
            int fh = hfr1.getHeight();
            for (int x = winX + 16; x < winX + winW - 16; x += fw) {
                g.drawImage(hfr1, x, winY, null);
                g.drawImage(hfr1, x, winY + winH - fh, null);
            }
        }

        // Vertical borders
        if (vfr1 != null) {
            int fw = vfr1.getWidth();
            int fh = vfr1.getHeight();
            for (int y = winY + 16; y < winY + winH - 16; y += fh) {
                g.drawImage(vfr1, winX, y, null);
                g.drawImage(vfr1, winX + winW - fw, y, null);
            }
        }

        // Corners
        if (corn != null) {
            int cw = corn.getWidth();
            int ch = corn.getHeight();
            g.drawImage(corn, winX, winY, null);
            g.drawImage(corn, winX + winW - cw, winY, null);
            g.drawImage(corn, winX, winY + winH - ch, null);
            g.drawImage(corn, winX + winW - cw, winY + winH - ch, null);
        }

        // Render in-game text using authentic "font moji" game asset (matching XIM / FFXI exactly)
        // 1. Speaker Header in authentic FFXI Gold
        font.drawString(sheet, "Lion", winX + 45, winY + 45, FontMojiRenderer.COLOR_GOLD, 2);

        // 2. Dialogue Text in authentic FFXI White
        font.drawString(sheet, "We've tracked the beastmen movements through the canyon.", winX + 45, winY + 95, FontMojiRenderer.COLOR_WHITE, 2);
        font.drawString(sheet, "Be on your guard--there is an ancient shadow awakening in these lands.", winX + 45, winY + 135, FontMojiRenderer.COLOR_WHITE, 2);
        font.drawString(sheet, "Will you stand with us and investigate the ruins?", winX + 45, winY + 175, FontMojiRenderer.COLOR_WHITE, 2);

        // 3. Selection choices
        int choiceY = winY + 250;
        font.drawString(sheet, "Accept the mission", winX + 90, choiceY, FontMojiRenderer.COLOR_WHITE, 2);
        font.drawString(sheet, "Ask for more time", winX + 90, choiceY + 36, FontMojiRenderer.COLOR_GREY, 2);

        // 4. Authentic cursor (yubi) pointing to active choice
        if (yubi != null) {
            g.drawImage(yubi, winX + 50, choiceY - 4, yubi.getWidth() * 2, yubi.getHeight() * 2, null);
        }

        // 5. System footer prompt
        font.drawString(sheet, "Press [Enter] or Controller (A) to proceed", winX + 45, winY + 345, FontMojiRenderer.COLOR_INFO_BLUE, 1);

        g.dispose();
        saveSnapshot(sheet, "ui_dialog_window_mockup.png");
    }

    // -------------------------------------------------------------------------
    // Helper Methods strictly using authentic game assets
    // -------------------------------------------------------------------------

    private static Map<String, BufferedImage> loadDecodedTextures(byte[] data, String resourceName) {
        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(data, resourceName));
        List<TextureResource> list = new ArrayList<>();
        collectTextures(graph.rootDirectory(), list);

        Map<String, BufferedImage> map = new LinkedHashMap<>();
        for (TextureResource tr : list) {
            byte[] rgba = TextureDecoder.decode(data, tr);
            if (rgba != null && tr.width() > 0 && tr.height() > 0) {
                BufferedImage img = toBufferedImage(rgba, tr.width(), tr.height());
                map.put(tr.sectionId().trim().toLowerCase(), img);
                String norm = normalize(tr.textureName());
                if (!norm.isEmpty()) {
                    map.put(norm, img);
                }
            }
        }
        return map;
    }

    private static String normalize(String name) {
        if (name == null) return "";
        String s = name.trim().toLowerCase();
        if (s.startsWith("tim") || s.startsWith("txd")) s = s.substring(3).trim();
        String[] parts = s.split("\\s+");
        return parts[parts.length - 1].replaceAll("[^a-zA-Z0-9_-]", "").trim();
    }

    private static void collectTextures(DirectoryResource dir, List<TextureResource> list) {
        if (dir == null) return;
        for (ResourceNode child : dir.children()) {
            if (child instanceof TextureResource tr) {
                list.add(tr);
            } else if (child instanceof DirectoryResource dr) {
                collectTextures(dr, list);
            }
        }
    }

    private static BufferedImage toBufferedImage(byte[] rgba, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int off = i * 4;
            if (off + 3 >= rgba.length) break;
            int r = rgba[off] & 0xFF;
            int g = rgba[off + 1] & 0xFF;
            int b = rgba[off + 2] & 0xFF;
            int a = rgba[off + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private static BufferedImage createSlateCanvas(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < height; y++) {
            float ratio = (float) y / height;
            int r = (int) (14 + ratio * 10);
            int gr = (int) (18 + ratio * 12);
            int b = (int) (26 + ratio * 16);
            g.setColor(new Color(r, gr, b));
            g.drawLine(0, y, width, y);
        }
        g.dispose();
        return img;
    }

    private void drawHeader(BufferedImage img, String title) {
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(25, 38, 56));
        g.fillRect(0, 0, img.getWidth(), 50);
        g.setColor(new Color(60, 95, 140));
        g.drawLine(0, 50, img.getWidth(), 50);
        g.dispose();
        font.drawString(img, title, 24, 16, FontMojiRenderer.COLOR_WHITE, 1);
    }

    private void drawTitledBox(BufferedImage img, int x, int y, int w, int h, String title) {
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 42, 60, 180));
        g.fillRect(x - 6, y, w + 12, h + 30);
        g.setColor(new Color(70, 100, 140));
        g.drawRect(x - 6, y, w + 12, h + 30);
        g.dispose();
        font.drawString(img, title, x, y + 6, FontMojiRenderer.COLOR_INFO_BLUE, 1);
    }

    private static void saveSnapshot(BufferedImage img, String filename) throws Exception {
        File out = OUTPUT_DIR.resolve(filename).toFile();
        ImageIO.write(img, "PNG", out);
        assertTrue(out.exists() && out.length() > 0, "Snapshot must exist: " + filename);
        System.out.printf("[UI-SNAPSHOT] Generated %s (%,d bytes, %dx%d)%n",
                out.getPath(), out.length(), img.getWidth(), img.getHeight());
    }
}
