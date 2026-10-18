package com.example.asset.interaction;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZoneInteractionParserTest {

    @Test
    void testParseInteractionsFromSouthSanDoria() throws Exception {
        String resourcePath = "/dats/zones/south_san_doria_230.DAT";
        byte[] bytes;
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(stream, "DAT asset must exist in test resources");
            bytes = stream.readAllBytes();
        }

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
        String resourcePath = "/dats/zones/west_ronfaure_100.DAT";
        byte[] bytes;
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(stream, "DAT asset must exist in test resources");
            bytes = stream.readAllBytes();
        }

        List<ZoneInteraction> interactions = ZoneInteractionParser.parseInteractions(bytes);
        assertNotNull(interactions);
        assertFalse(interactions.isEmpty(), "West Ronfaure should contain zone interaction volumes");

        long zoneLines = interactions.stream().filter(ZoneInteraction::isZoneLine).count();
        long entrances = interactions.stream().filter(ZoneInteraction::isZoneEntrance).count();
        assertTrue(zoneLines > 0, "West Ronfaure should contain zonelines");
        assertTrue(entrances > 0, "West Ronfaure should contain entrances");
    }
}
