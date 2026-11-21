package com.ffxi.viewer.renderer;

import com.example.asset.config.FfxiPathConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FontMojiRendererLayoutTest {

    @Test
    @DisplayName("layoutString emits proportional quads with newline spacing, omits spaces, and stays in [0, 1] UVs")
    void testLayoutStringAndToRgbaBytes() {
        BufferedImage atlas = new BufferedImage(1024, 2048, BufferedImage.TYPE_INT_ARGB);
        atlas.setRGB(0, 0, 0x80112233);
        atlas.setRGB(1, 0, 0xFF445566);

        FontMojiRenderer font = new FontMojiRenderer(atlas);
        assertEquals(1024, font.getTexWidth());
        assertEquals(2048, font.getTexHeight());

        float scale = 2.0f;
        assertEquals((float) font.measureString("Aldo", 2), font.measureStringWidth("Aldo", scale), 1e-4f);
        assertEquals(font.measureStringWidth("Aldo", scale), font.measureStringWidth("Aldo\nNPC", scale), 1e-4f);

        List<FontMojiRenderer.GlyphQuad> quads = font.layoutString("A B\nC", 10.0f, 20.0f, scale);
        assertEquals(3, quads.size(), "Space advances cursor without emitting a quad");

        FontMojiRenderer.GlyphQuad first = quads.get(0);
        FontMojiRenderer.GlyphQuad second = quads.get(1);
        FontMojiRenderer.GlyphQuad third = quads.get(2);

        assertEquals(10.0f, first.x0(), 1e-4f);
        assertEquals(20.0f, first.y0(), 1e-4f);
        assertTrue(second.x0() > first.x1(), "Space and glyph advance must separate 'A' and 'B'");
        assertEquals(20.0f, second.y0(), 1e-4f);

        assertEquals(10.0f, third.x0(), 1e-4f);
        assertEquals(20.0f + 16.0f * scale, third.y0(), 1e-4f, "Newline must advance Y by 16 * scale");

        for (FontMojiRenderer.GlyphQuad q : quads) {
            assertTrue(q.u0() >= 0.0f && q.u0() < q.u1() && q.u1() <= 1.0f);
            assertTrue(q.v0() >= 0.0f && q.v0() < q.v1() && q.v1() <= 1.0f);
        }

        byte[] rgba = font.toRgbaBytes();
        assertEquals(1024 * 2048 * 4, rgba.length);
        assertEquals((byte) 0x11, rgba[0]);
        assertEquals((byte) 0x22, rgba[1]);
        assertEquals((byte) 0x33, rgba[2]);
        assertEquals((byte) 0x80, rgba[3]);
        assertEquals((byte) 0x44, rgba[4]);
        assertEquals((byte) 0x55, rgba[5]);
        assertEquals((byte) 0x66, rgba[6]);
        assertEquals((byte) 0xFF, rgba[7]);
    }

    @Test
    @DisplayName("Retail ROM/0/1.DAT FontMoji atlas loads and produces GPU layout quads when FFXI assets exist")
    void testRetailFontMojiAtlasLayout() throws IOException {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        assumeTrue(Files.isRegularFile(ffxiRoot.resolve("ROM/0/1.DAT")),
                "Retail FFXI ROM/0/1.DAT not found under " + ffxiRoot);

        FontMojiRenderer font = FontMojiRenderer.load(ffxiRoot);
        assertEquals(1024, font.getTexWidth());
        assertEquals(2048, font.getTexHeight());
        assertTrue(font.measureStringWidth("[NPC] Raminel", 1.0f) > 0.0f);
        assertFalse(font.layoutString("[NPC] Raminel", 100.0f, 50.0f, 1.0f).isEmpty());
    }
}
