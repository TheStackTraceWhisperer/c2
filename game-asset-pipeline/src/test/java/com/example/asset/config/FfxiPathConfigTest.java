package com.example.asset.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FfxiPathConfigTest {

    @Test
    @DisplayName("FfxiPathConfig resolves default Linux Lutris/Wine path")
    void testDefaultLinuxInstallPath() {
        Path installDir = FfxiPathConfig.resolveFfxiInstallDirectory();
        assertNotNull(installDir);

        String userHome = System.getProperty("user.home", System.getenv("HOME"));
        Path expected = Path.of(userHome, "Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI");
        assertEquals(expected.toAbsolutePath(), installDir.toAbsolutePath());
    }

    @Test
    @DisplayName("FfxiPathConfig respects system property override ffxi.home")
    void testSystemPropertyOverride(@TempDir Path tempDir) {
        String prev = System.getProperty("ffxi.home");
        try {
            System.setProperty("ffxi.home", tempDir.toAbsolutePath().toString());
            Path resolved = FfxiPathConfig.resolveFfxiInstallDirectory();
            assertEquals(tempDir.toAbsolutePath(), resolved.toAbsolutePath());
        } finally {
            if (prev != null) {
                System.setProperty("ffxi.home", prev);
            } else {
                System.clearProperty("ffxi.home");
            }
        }
    }

    @Test
    @DisplayName("FfxiPathConfig resolves files case-insensitively")
    void testCaseInsensitiveResolution(@TempDir Path tempDir) throws IOException {
        String prev = System.getProperty("ffxi.home");
        try {
            System.setProperty("ffxi.home", tempDir.toAbsolutePath().toString());

            // Create nested structure: rom/27/82.dat (lowercase)
            Path romDir = tempDir.resolve("rom").resolve("27");
            Files.createDirectories(romDir);
            Path datFile = romDir.resolve("82.dat");
            Files.writeString(datFile, "TEST_DATA");

            // Look up as ROM/27/82.DAT (uppercase)
            Optional<Path> found = FfxiPathConfig.resolveRomFile("ROM/27/82.DAT");
            assertTrue(found.isPresent(), "Should locate lowercase rom/27/82.dat when querying ROM/27/82.DAT");
            assertEquals(datFile.toAbsolutePath(), found.get().toAbsolutePath());
        } finally {
            if (prev != null) {
                System.setProperty("ffxi.home", prev);
            } else {
                System.clearProperty("ffxi.home");
            }
        }
    }
}
