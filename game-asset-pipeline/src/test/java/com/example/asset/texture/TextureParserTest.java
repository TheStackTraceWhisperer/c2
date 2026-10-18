package com.example.asset.texture;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.parser.DatSectionScanner;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TextureParserTest {

    @Test
    @DisplayName("Successfully parse and decompress real DXT3 textures from South San d'Oria DAT")
    void testParseRealDxt3TexturesFromZoneDat() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/dats/zones/south_san_doria_230.DAT");
        assumeTrue(stream != null, "Fixture south_san_doria_230.DAT is missing");

        byte[] datBytes = stream.readAllBytes();
        DatByteReader reader = new DatByteReader(datBytes);
        List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);

        List<DatSectionHeader> textureSections = sections.stream()
                .filter(s -> s.sectionType() == SectionType.S20_TEXTURE)
                .toList();

        assertFalse(textureSections.isEmpty(), "South San d'Oria should contain S20_TEXTURE sections");
        System.out.printf("Found %d texture sections in South San d'Oria%n", textureSections.size());

        int decodedCount = 0;
        for (DatSectionHeader header : textureSections) {
            DatByteReader payload = DatSectionScanner.extractPayload(reader, header);
            Optional<DecodedTexture> texOpt = TextureParser.parseTexture(payload);

            if (texOpt.isPresent()) {
                DecodedTexture tex = texOpt.get();
                assertNotNull(tex.name());
                assertTrue(tex.width() > 0);
                assertTrue(tex.height() > 0);
                assertNotNull(tex.format());
                assertEquals(tex.width() * tex.height() * 4, tex.rgbaPixels().length,
                        "Decompressed RGBA pixels must match width * height * 4");
                decodedCount++;
            }
        }

        assertTrue(decodedCount >= 40, "Expected at least 40 textures to decode successfully");
        System.out.printf("Successfully decoded %d textures to RGBA8888!%n", decodedCount);
    }

    @Test
    @DisplayName("DxtDecompressor handles synthetic DXT1 solid block")
    void testDecompressSyntheticDxt1Block() {
        // 4x4 block with c0 = 0xF800 (Pure Red RGB565), c1 = 0x001F (Pure Blue RGB565)
        // 16 pixels all using color 0 (indices = 0)
        byte[] dxt1Data = new byte[]{
                (byte) 0x00, (byte) 0xF8, // c0 = Red
                (byte) 0x1F, (byte) 0x00, // c1 = Blue
                0x00, 0x00, 0x00, 0x00    // indices = all 0
        };

        byte[] rgba = DxtDecompressor.decompressDxt1(dxt1Data, 4, 4);
        assertEquals(4 * 4 * 4, rgba.length);

        // Check top-left pixel (red = 255, green = 0, blue = 0, alpha = 255)
        assertEquals((byte) 255, rgba[0]); // R
        assertEquals((byte) 0, rgba[1]);   // G
        assertEquals((byte) 0, rgba[2]);   // B
        assertEquals((byte) 255, rgba[3]); // A
    }
}
