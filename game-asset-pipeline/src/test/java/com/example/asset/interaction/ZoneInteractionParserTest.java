package com.example.asset.interaction;

import com.example.asset.config.FfxiPathConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZoneInteractionParserTest {

    private byte[] loadZoneDat(String resourcePath, String relRom) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        }
        var romOpt = FfxiPathConfig.resolveRomFile(relRom);
        if (romOpt.isPresent() && Files.isRegularFile(romOpt.get())) {
            return Files.readAllBytes(romOpt.get());
        }
        return null;
    }

    @Test
    void testParseInteractionsFromSouthSanDoria() throws Exception {
        byte[] bytes = loadZoneDat("/dats/zones/south_san_doria_230.DAT", "ROM/1/31.DAT");
        Assumptions.assumeTrue(bytes != null, "DAT asset must exist in test resources or retail install");

        List<ZoneInteraction> interactions = ZoneInteractionParser.parseInteractions(bytes);
        assertNotNull(interactions);
        assertFalse(interactions.isEmpty(), "South San d'Oria should contain zone interaction volumes");

        long zoneLines = interactions.stream().filter(ZoneInteraction::isZoneLine).count();
        long entrances = interactions.stream().filter(ZoneInteraction::isZoneEntrance).count();

        assertTrue(zoneLines > 0, "Should contain zonelines (target gates)");
        assertTrue(entrances > 0, "Should contain zone entrances (spawn points)");

        // Test one zoneline bounding box check
        ZoneInteraction sampleZoneline = interactions.stream()
                .filter(ZoneInteraction::isZoneLine)
                .findFirst()
                .orElseThrow();

        assertNotNull(sampleZoneline.getSourceId());
        assertNotNull(sampleZoneline.getDestId());
        assertNotNull(sampleZoneline.getBoundingBox());

        // The center coordinate must be contained within its own bounding box
        assertTrue(sampleZoneline.contains(
                sampleZoneline.getPosition().x(),
                sampleZoneline.getPosition().y(),
                sampleZoneline.getPosition().z()
        ));
    }

    @Test
    void testParseInteractionsFromWestRonfaure() throws Exception {
        byte[] bytes = loadZoneDat("/dats/zones/west_ronfaure_100.DAT", "ROM/1/30.DAT");
        Assumptions.assumeTrue(bytes != null, "DAT asset must exist in test resources or retail install");

        List<ZoneInteraction> interactions = ZoneInteractionParser.parseInteractions(bytes);
        assertNotNull(interactions);
        assertFalse(interactions.isEmpty(), "West Ronfaure should contain zone interaction volumes");

        long fishingAreas = interactions.stream().filter(ZoneInteraction::isFishingArea).count();
        long doors = interactions.stream().filter(ZoneInteraction::isDoor).count();
        assertTrue(fishingAreas > 0, "West Ronfaure should contain fishing areas");
        assertTrue(doors > 0, "West Ronfaure should contain doors/environmental interactions");
    }
}
