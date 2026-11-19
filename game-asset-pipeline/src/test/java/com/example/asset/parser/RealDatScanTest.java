package com.example.asset.parser;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.asset.config.FfxiPathConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RealDatScanTest {

    @Test
    @DisplayName("Should scan sections from real system 0.DAT resource")
    void testScanSystemDat() throws Exception {
        {
            Optional<Path> dat = FfxiPathConfig.resolveRomFile("ROM/0/0.DAT");
            org.junit.jupiter.api.Assumptions.assumeTrue(dat.isPresent(), "Retail FFXI ROM/0/0.DAT not present under " + FfxiPathConfig.resolveFfxiInstallDirectory() + "; skipping.");
            byte[] bytes = Files.readAllBytes(dat.get());
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
