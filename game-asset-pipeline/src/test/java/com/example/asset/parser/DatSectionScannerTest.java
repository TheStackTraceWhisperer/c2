package com.example.asset.parser;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DatSectionScannerTest {

    @Test
    @DisplayName("Scan all section headers in South San d'Oria DAT")
    void testScanSouthSanDoriaSections() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/dats/zones/south_san_doria_230.DAT");
        assumeTrue(stream != null, "Missing south_san_doria_230.DAT fixture");

        byte[] bytes = stream.readAllBytes();
        DatByteReader reader = new DatByteReader(bytes);
        List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);

        assertNotNull(sections);
        assertFalse(sections.isEmpty());

        System.out.println("Total sections found in South San d'Oria: " + sections.size());
        Map<String, Long> countByType = sections.stream()
                .collect(Collectors.groupingBy(s -> s.sectionType().name(), Collectors.counting()));
        countByType.forEach((type, count) -> System.out.printf("  %s : %d%n", type, count));

        // Inspect first S20_TEXTURE section
        sections.stream()
                .filter(s -> s.sectionType() == com.example.asset.model.SectionType.S20_TEXTURE)
                .limit(2)
                .forEach(s -> {
                    DatByteReader payload = DatSectionScanner.extractPayload(reader, s);
                    byte[] bytes64 = payload.nextBytes(Math.min(64, payload.remaining()));
                    System.out.printf("Texture Section '%s' (payload size %d):%n", s.sectionId(), payload.remaining() + 64);
                    for (int i = 0; i < bytes64.length; i += 16) {
                        StringBuilder hex = new StringBuilder();
                        StringBuilder asc = new StringBuilder();
                        for (int j = 0; j < 16 && (i + j) < bytes64.length; j++) {
                            int b = bytes64[i + j] & 0xFF;
                            hex.append(String.format("%02X ", b));
                            asc.append((b >= 32 && b < 127) ? (char) b : '.');
                        }
                        System.out.printf("  %04X: %-48s | %s%n", i, hex, asc);
                    }
                });

        // Inspect first S2E_ZONE_MESH section with ZoneDecrypt.decryptZoneMesh
        sections.stream()
                .filter(s -> s.sectionType() == com.example.asset.model.SectionType.S2E_ZONE_MESH)
                .limit(2)
                .forEach(s -> {
                    com.example.asset.decoder.ZoneDecrypt.decryptZoneMesh(s, reader);

                    reader.position(s.dataStartPosition());
                    int decryptInfo = reader.nextI32();
                    int keyConfigData = reader.nextI32();
                    String author = reader.nextString(8);
                    int configData = keyConfigData & 0xFF;
                    int meshTypeFlag = configData & 1;
                    boolean vertexBlendEnabled = (configData & 2) != 0;
                    String meshName = reader.nextString(16).trim();

                    System.out.printf("Decrypted ZoneMesh '%s' (id='%s'): author='%s', meshTypeFlag=%d, blend=%b%n",
                            meshName, s.sectionId(), author, meshTypeFlag, vertexBlendEnabled);

                    int defStart = reader.position();
                    int meshCount0 = reader.nextI32();
                    System.out.printf("  meshCount0=%d, defStart=%d%n", meshCount0, defStart);
                });
    }
}
