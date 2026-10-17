package com.example.asset.parser;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RealDatScanTest {

    @Test
    @DisplayName("Should scan sections from real system 0.DAT resource")
    void testScanSystemDat() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/dats/system/0.DAT")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(is != null, "Test asset /dats/system/0.DAT not present; skipping.");
            byte[] bytes = is.readAllBytes();
            DatByteReader reader = new DatByteReader(bytes);

            List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);

            assertThat(sections).isNotEmpty();
            // First section in 0.DAT is a Directory (code 0x01)
            DatSectionHeader rootDir = sections.get(0);
            assertThat(rootDir.sectionType()).isEqualTo(SectionType.S01_DIRECTORY);
            assertThat(rootDir.sectionSize()).isGreaterThan(0);
        }
    }
}
